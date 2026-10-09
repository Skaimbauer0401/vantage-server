package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastLight;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's increase propagation (the bulk of light work), step for step: same queue order,
 * same reads, same writes, same affected-section bookkeeping. Only the lookups change: light
 * sections and block sections come from small caches instead of a hash map lookup (and a
 * chunk lookup) for every neighbor.
 *
 * <p>The light section cache is coherent: while increases propagate, the updating section
 * map only changes through {@code copyDataLayer}, which happens here (the one exception,
 * sky light's empty-section propagation, writes through vanilla and resets the caches).
 * Block sections are fixed arrays per chunk, read with the same rules as the chunk's own
 * {@code getBlockState}.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
	@Shadow
	@Final
	protected LayerLightSectionStorage<?> storage;
	@Shadow
	@Final
	private LongArrayFIFOQueue increaseQueue;

	@Shadow
	protected abstract BlockState getState(BlockPos pos);

	@Shadow
	protected abstract int getOpacity(BlockState state);

	@Shadow
	protected abstract boolean shapeOccludes(BlockState fromState, BlockState toState, Direction direction);

	@Shadow
	protected abstract void enqueueIncrease(long fromNode, long increaseData);

	@Shadow
	protected abstract @Nullable LightChunk getChunk(int chunkX, int chunkZ);

	@Shadow
	protected static boolean isEmptyShape(BlockState state) {
		throw new AssertionError();
	}

	@Unique
	private static final Direction[] mcrs$DIRECTIONS = Direction.values();

	@Unique
	private final long[] mcrs$layerKeys = new long[256];
	@Unique
	private final DataLayer[] mcrs$layers = new DataLayer[256];
	@Unique
	private final boolean[] mcrs$layerValid = new boolean[256];
	@Unique
	private final long[] mcrs$blockKeys = new long[64];
	@Unique
	private final Object[] mcrs$blockSections = new Object[64];
	@Unique
	private final boolean[] mcrs$blockValid = new boolean[64];
	@Unique
	private long mcrs$lastAffected = Long.MAX_VALUE;

	@Inject(method = "propagateIncreases", at = @At("HEAD"), cancellable = true)
	private void mcrs$propagateIncreases(CallbackInfoReturnable<Integer> cir) {
		Class<?> type = ((Object) this).getClass();
		boolean sky = type == SkyLightEngine.class;
		if (!FastLight.ENABLED || !(sky || type == BlockLightEngine.class)) {
			return;
		}
		mcrs$reset();
		int count;
		for (count = 0; !this.increaseQueue.isEmpty(); count++) {
			long fromNode = this.increaseQueue.dequeueLong();
			long increaseData = this.increaseQueue.dequeueLong();
			int fromLevel = mcrs$getStoredLevel(fromNode);
			int fromTargetLevel = LightEngine.QueueEntry.getFromLevel(increaseData);
			if (LightEngine.QueueEntry.isIncreaseFromEmission(increaseData) && fromLevel < fromTargetLevel) {
				mcrs$setStoredLevel(fromNode, fromTargetLevel);
				fromLevel = fromTargetLevel;
			}

			if (fromLevel == fromTargetLevel) {
				mcrs$increase(fromNode, increaseData, fromLevel, sky);
			}
		}
		// Caches must not outlive this call: other code changes the maps and chunks.
		mcrs$reset();
		cir.setReturnValue(count);
	}

	/** Sky and block {@code propagateIncrease}; they differ only in sky light's empty-section step. */
	@Unique
	private void mcrs$increase(long fromNode, long increaseData, int fromLevel, boolean sky) {
		BlockState fromState = null;
		int emptySectionsBelow = sky ? ((SkyLightEngineInvoker) this).mcrs$countEmptySectionsBelowIfAtBorder(fromNode) : 0;

		for (Direction direction : mcrs$DIRECTIONS) {
			if (LightEngine.QueueEntry.shouldPropagateInDirection(increaseData, direction)) {
				long toNode = BlockPos.offset(fromNode, direction);
				DataLayer toLayer = mcrs$layer(SectionPos.blockToSection(toNode));
				if (toLayer != null) {
					int toLevel = toLayer.get(SectionPos.sectionRelative(BlockPos.getX(toNode)),
							SectionPos.sectionRelative(BlockPos.getY(toNode)), SectionPos.sectionRelative(BlockPos.getZ(toNode)));
					int maxPossibleNewToLevel = fromLevel - 1;
					if (maxPossibleNewToLevel > toLevel) {
						BlockState toState = mcrs$state(toNode);
						int newToLevel = fromLevel - this.getOpacity(toState);
						if (newToLevel > toLevel) {
							if (fromState == null) {
								fromState = LightEngine.QueueEntry.isFromEmptyShape(increaseData) ? Blocks.AIR.defaultBlockState() : mcrs$state(fromNode);
							}

							if (!this.shapeOccludes(fromState, toState, direction)) {
								mcrs$setStoredLevel(toNode, newToLevel);
								if (newToLevel > 1) {
									this.enqueueIncrease(toNode,
											LightEngine.QueueEntry.increaseSkipOneDirection(newToLevel, isEmptyShape(toState), direction.getOpposite()));
								}

								if (sky && emptySectionsBelow != 0) {
									// Writes through the storage, possibly copying layers: drop the caches.
									((SkyLightEngineInvoker) this).mcrs$propagateFromEmptySections(toNode, direction, newToLevel, true, emptySectionsBelow);
									mcrs$reset();
								}
							}
						}
					}
				}
			}
		}
	}

	@Unique
	private void mcrs$reset() {
		Arrays.fill(mcrs$layerValid, false);
		Arrays.fill(mcrs$layers, null);
		Arrays.fill(mcrs$blockValid, false);
		Arrays.fill(mcrs$blockSections, null);
		mcrs$lastAffected = Long.MAX_VALUE;
	}

	@Unique
	private static int mcrs$slot(long key, int mask) {
		return (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
	}

	/** {@code storage.getDataLayer(sectionNode, true)}. */
	@Unique
	private @Nullable DataLayer mcrs$layer(long sectionNode) {
		int slot = mcrs$slot(sectionNode, 255);
		if (mcrs$layerValid[slot] && mcrs$layerKeys[slot] == sectionNode) {
			DataLayer cached = mcrs$layers[slot];
			if (FastLight.VERIFY) {
				FastLight.check(cached == ((LayerLightSectionStorageAccessor) storage).mcrs$updating().getLayer(sectionNode), "light section");
			}
			return cached;
		}
		DataLayer layer = ((LayerLightSectionStorageAccessor) storage).mcrs$updating().getLayer(sectionNode);
		mcrs$layerKeys[slot] = sectionNode;
		mcrs$layers[slot] = layer;
		mcrs$layerValid[slot] = true;
		return layer;
	}

	/** {@code storage.getStoredLevel(blockNode)}. */
	@Unique
	private int mcrs$getStoredLevel(long blockNode) {
		DataLayer layer = mcrs$layer(SectionPos.blockToSection(blockNode));
		return layer.get(SectionPos.sectionRelative(BlockPos.getX(blockNode)),
				SectionPos.sectionRelative(BlockPos.getY(blockNode)), SectionPos.sectionRelative(BlockPos.getZ(blockNode)));
	}

	/** {@code storage.setStoredLevel(blockNode, level)}. */
	@Unique
	private void mcrs$setStoredLevel(long blockNode, int level) {
		LayerLightSectionStorageAccessor access = (LayerLightSectionStorageAccessor) storage;
		long sectionNode = SectionPos.blockToSection(blockNode);
		DataLayer layer;
		if (access.mcrs$changedSections().add(sectionNode)) {
			layer = access.mcrs$updating().copyDataLayer(sectionNode);
			int slot = mcrs$slot(sectionNode, 255);
			mcrs$layerKeys[slot] = sectionNode;
			mcrs$layers[slot] = layer;
			mcrs$layerValid[slot] = true;
		} else {
			layer = mcrs$layer(sectionNode);
		}

		int x = BlockPos.getX(blockNode);
		int y = BlockPos.getY(blockNode);
		int z = BlockPos.getZ(blockNode);
		layer.set(SectionPos.sectionRelative(x), SectionPos.sectionRelative(y), SectionPos.sectionRelative(z), level);
		// SectionPos.aroundAndAtBlockPos. Adding a key the set already holds is a no-op, so an
		// interior block of the section added last needs no add.
		LongSet affected = access.mcrs$sectionsAffected();
		if (((x + 1) & 15) > 1 && ((y + 1) & 15) > 1 && ((z + 1) & 15) > 1) {
			if (sectionNode != mcrs$lastAffected) {
				affected.add(sectionNode);
				mcrs$lastAffected = sectionNode;
			}
		} else {
			SectionPos.aroundAndAtBlockPos(blockNode, affected::add);
		}
	}

	/** {@code getState(pos)}. */
	@Unique
	private BlockState mcrs$state(long node) {
		BlockState state = mcrs$fastState(node);
		if (FastLight.VERIFY) {
			FastLight.check(state == this.getState(BlockPos.of(node)), "block state");
		}
		return state;
	}

	@Unique
	private BlockState mcrs$fastState(long node) {
		int x = BlockPos.getX(node);
		int y = BlockPos.getY(node);
		int z = BlockPos.getZ(node);
		long sectionNode = SectionPos.blockToSection(node);
		int slot = mcrs$slot(sectionNode, 63);
		Object cached;
		if (mcrs$blockValid[slot] && mcrs$blockKeys[slot] == sectionNode) {
			cached = mcrs$blockSections[slot];
		} else {
			cached = mcrs$resolve(SectionPos.blockToSectionCoord(x), y, SectionPos.blockToSectionCoord(z));
			mcrs$blockKeys[slot] = sectionNode;
			mcrs$blockSections[slot] = cached;
			mcrs$blockValid[slot] = true;
		}
		if (cached instanceof LevelChunkSection section) {
			return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
		}
		if (cached instanceof BlockState fixed) {
			return fixed;
		}
		return this.getState(BlockPos.of(node));
	}

	/**
	 * What the chunk's {@code getBlockState} reads for blocks of this section: the section, a
	 * fixed state, or null for "ask vanilla". Mirrors ProtoChunk and LevelChunk (through
	 * ImposterProtoChunk, which only delegates); anything else goes through vanilla.
	 */
	@Unique
	private @Nullable Object mcrs$resolve(int chunkX, int y, int chunkZ) {
		LightChunk chunk = this.getChunk(chunkX, chunkZ);
		if (chunk == null) {
			return Blocks.BEDROCK.defaultBlockState();
		}
		if (chunk.getClass() == ProtoChunk.class) {
			ProtoChunk proto = (ProtoChunk) chunk;
			if (proto.isOutsideBuildHeight(y)) {
				return Blocks.VOID_AIR.defaultBlockState();
			}
			return proto.getSection(proto.getSectionIndex(y));
		}
		if (chunk.getClass() == ImposterProtoChunk.class) {
			LevelChunk level = ((ImposterProtoChunk) chunk).getWrapped();
			if (level.getClass() != LevelChunk.class || level.getLevel().isDebug()) {
				return null;
			}
			int index = level.getSectionIndex(y);
			LevelChunkSection[] sections = level.getSections();
			return index >= 0 && index < sections.length ? sections[index] : Blocks.AIR.defaultBlockState();
		}
		return null;
	}
}
