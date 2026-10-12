package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.mcrs.bridge.FastEntities;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Optional;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * For every unpacked chunk column, a bit mask of its sections that are present (vanilla never
 * drops sections from {@code storage}; an unpacked column has all of its sections in it, and
 * only {@code getOrCreate} turns an empty one present). Lets the POI manager skip columns
 * without any section; see {@link FastEntities}.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageColumnsMixin implements FastEntities.Columns {
	@Shadow
	@Final
	private Long2ObjectMap<Optional<?>> storage;

	@Shadow
	@Final
	protected LevelHeightAccessor levelHeightAccessor;

	@Unique
	private final Long2LongOpenHashMap mcrs$columns = mcrs$newColumns();

	@Unique
	private static Long2LongOpenHashMap mcrs$newColumns() {
		Long2LongOpenHashMap map = new Long2LongOpenHashMap();
		map.defaultReturnValue(-1L);
		return map;
	}

	@Override
	public long mcrs$presentSections(long chunkKey) {
		return mcrs$columns.get(chunkKey);
	}

	@Override
	public Optional<?> mcrs$storedSection(long sectionKey) {
		return storage.get(sectionKey);
	}

	@Shadow
	protected abstract Optional<?> getOrLoad(long sectionKey);

	@Override
	public Optional<?> mcrs$getOrLoad(long sectionKey) {
		return getOrLoad(sectionKey);
	}

	@Override
	public int mcrs$minSectionY() {
		return levelHeightAccessor.getMinSectionY();
	}

	@Unique
	private boolean mcrs$trackable() {
		return FastEntities.ENABLED && levelHeightAccessor.getSectionsCount() <= 63;
	}

	@Inject(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V", at = @At("RETURN"))
	private void mcrs$columnUnpacked(CallbackInfo ci, @Local(argsOnly = true) ChunkPos pos) {
		if (!mcrs$trackable()) {
			return;
		}
		int minY = levelHeightAccessor.getMinSectionY();
		long mask = 0L;
		for (int sectionY = minY; sectionY <= levelHeightAccessor.getMaxSectionY(); sectionY++) {
			Optional<?> section = storage.get(SectionPos.asLong(pos.x(), sectionY, pos.z()));
			if (section == null) {
				return;
			}
			if (section.isPresent()) {
				mask |= 1L << (sectionY - minY);
			}
		}
		mcrs$columns.put(pos.pack(), mask);
	}

	@Inject(method = "getOrCreate", at = @At("RETURN"))
	private void mcrs$sectionCreated(long sectionPos, CallbackInfoReturnable<Object> cir) {
		long chunkKey = ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos));
		long mask = mcrs$columns.get(chunkKey);
		if (mask != -1L) {
			mcrs$columns.put(chunkKey, mask | 1L << (SectionPos.y(sectionPos) - levelHeightAccessor.getMinSectionY()));
		}
	}
}
