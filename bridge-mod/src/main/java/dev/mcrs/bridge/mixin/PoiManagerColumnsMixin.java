package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A chunk column that is unpacked and has no POI section yields no records; vanilla would look
 * up each of its sections (without side effects, as the column is unpacked) to find that out.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerColumnsMixin {
	@Inject(method = "getInChunk", at = @At("HEAD"), cancellable = true)
	private void mcrs$emptyColumn(Predicate<Holder<PoiType>> predicate, ChunkPos chunkPos, PoiManager.Occupancy occupancy, CallbackInfoReturnable<Stream<PoiRecord>> cir) {
		if (((FastEntities.Columns) this).mcrs$presentSections(chunkPos.pack()) == 0L) {
			cir.setReturnValue(Stream.empty());
		}
	}
}
