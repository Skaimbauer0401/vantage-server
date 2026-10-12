package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code getInChunk} of an unpacked chunk column visits only its present POI sections, bottom
 * to top as vanilla: vanilla looks up every section of the column (without side effects, as
 * the column is unpacked) and skips the absent ones. A column without any section yields no
 * records. Columns that are not unpacked go the vanilla way, which may load them.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerColumnsMixin {
	@Inject(method = "getInChunk", at = @At("HEAD"), cancellable = true)
	private void mcrs$presentSections(Predicate<Holder<PoiType>> predicate, ChunkPos chunkPos, PoiManager.Occupancy occupancy,
			CallbackInfoReturnable<Stream<PoiRecord>> cir) {
		FastEntities.Columns columns = (FastEntities.Columns) this;
		long mask = columns.mcrs$presentSections(chunkPos.pack());
		if (mask == -1L) {
			return;
		}
		if (mask == 0L) {
			cir.setReturnValue(Stream.empty());
			return;
		}
		if (!FastEntities.FAST_POI) {
			return;
		}
		int minY = columns.mcrs$minSectionY();
		List<PoiSection> sections = new ArrayList<>(Long.bitCount(mask));
		for (long bits = mask; bits != 0L; bits &= bits - 1) {
			int sectionY = minY + Long.numberOfTrailingZeros(bits);
			Optional<?> section = columns.mcrs$storedSection(SectionPos.asLong(chunkPos.x(), sectionY, chunkPos.z()));
			if (section == null) {
				// Not the expected state of an unpacked column: let vanilla answer.
				return;
			}
			if (section.isPresent()) {
				sections.add((PoiSection) section.get());
			}
		}
		if (FastEntities.FAST_POI_VERIFY) {
			List<PoiRecord> mine = sections.stream().flatMap(section -> section.getRecords(predicate, occupancy)).toList();
			List<PoiRecord> vanilla = new ArrayList<>();
			for (int y = columns.mcrs$minSectionY(); y <= columns.mcrs$minSectionY() + 63; y++) {
				long key = SectionPos.asLong(chunkPos.x(), y, chunkPos.z());
				Optional<?> section = columns.mcrs$storedSection(key);
				if (section == null) {
					break;
				}
				Optional<?> loaded = columns.mcrs$getOrLoad(key);
				if (loaded.isPresent()) {
					((PoiSection) loaded.get()).getRecords(predicate, occupancy).forEach(vanilla::add);
				}
			}
			if (mine.equals(vanilla)) {
				FastEntities.POI_VERIFIED.incrementAndGet();
			} else {
				FastEntities.POI_MISMATCHED.incrementAndGet();
			}
			cir.setReturnValue(mine.stream());
			return;
		}
		cir.setReturnValue(sections.size() == 1 ? sections.get(0).getRecords(predicate, occupancy)
				: sections.stream().flatMap(section -> section.getRecords(predicate, occupancy)));
	}
}
