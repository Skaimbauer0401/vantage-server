package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastLight;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code SkyLightEngine.propagateLightSources}, the per-chunk sky source fill, with the same
 * result: every source block above the chunk's lowest sources set to 15 (materializing each
 * written section's byte array, as vanilla's {@code set} does) and the same increase
 * entries in the same order. Rows where every column is a source are written as whole
 * 128-byte rows instead of 256 nibble writes.
 *
 * <p>With {@code -Dmcrs.fastLight.verify=true} the fast fill runs on copies first, vanilla
 * then runs for real, and the bytes and queue entries are compared.
 */
@Mixin(SkyLightEngine.class)
public abstract class SkyLightSourcesMixin {
	@Shadow
	@Final
	private ChunkSkyLightSources emptyChunkSources;

	@Shadow
	private @Nullable ChunkSkyLightSources getChunkSources(int chunkX, int chunkZ) {
		throw new AssertionError();
	}

	@Unique
	private List<Object> mcrs$expected;
	@Unique
	private int mcrs$queueSizeBefore;

	@Inject(method = "propagateLightSources", at = @At("HEAD"), cancellable = true)
	private void mcrs$propagateLightSources(ChunkPos pos, CallbackInfo ci) {
		if (!FastLight.ENABLED || ((Object) this).getClass() != SkyLightEngine.class) {
			return;
		}
		if (FastLight.VERIFY) {
			LightEngineAccessor engine = (LightEngineAccessor) this;
			mcrs$queueSizeBefore = engine.mcrs$increaseQueue().size();
			mcrs$expected = mcrs$fill(pos, true);
			return;
		}
		mcrs$fill(pos, false);
		ci.cancel();
	}

	@Inject(method = "propagateLightSources", at = @At("RETURN"))
	private void mcrs$verify(ChunkPos pos, CallbackInfo ci) {
		if (!FastLight.VERIFY || mcrs$expected == null) {
			return;
		}
		List<Object> expected = mcrs$expected;
		mcrs$expected = null;
		LightEngineAccessor engine = (LightEngineAccessor) this;
		LayerLightSectionStorageAccessor storage = (LayerLightSectionStorageAccessor) engine.mcrs$storage();
		// expected: [LongArrayList queue entries, then (Long sectionNode, DataLayer layer) pairs]
		LongArrayList entries = (LongArrayList) expected.get(0);
		LongArrayFIFOQueue queue = engine.mcrs$increaseQueue();
		long[] all = new long[queue.size()];
		for (int i = 0; i < all.length; i++) {
			all[i] = queue.dequeueLong();
		}
		for (long v : all) {
			queue.enqueue(v);
		}
		long[] added = Arrays.copyOfRange(all, mcrs$queueSizeBefore, all.length);
		FastLight.check(Arrays.equals(added, entries.toLongArray()), "sky source queue");
		for (int i = 1; i < expected.size(); i += 2) {
			long sectionNode = (Long) expected.get(i);
			DataLayer mine = (DataLayer) expected.get(i + 1);
			DataLayer actual = storage.mcrs$updating().getLayer(sectionNode);
			boolean same = actual != null && mine.isDefinitelyHomogenous() == actual.isDefinitelyHomogenous()
					&& (mine.isDefinitelyHomogenous() ? mine.get(0, 0, 0) == actual.get(0, 0, 0) : Arrays.equals(mine.getData(), actual.getData()));
			FastLight.check(same, "sky source section");
		}
	}

	/**
	 * Runs the fill. With {@code dry}, works on copies of the current layers, enqueues
	 * nothing and returns what it would have done (for verify); else returns null.
	 */
	@Unique
	private @Nullable List<Object> mcrs$fill(ChunkPos pos, boolean dry) {
		LightEngineAccessor engine = (LightEngineAccessor) this;
		LayerLightSectionStorage<?> rawStorage = engine.mcrs$storage();
		LayerLightSectionStorageAccessor storage = (LayerLightSectionStorageAccessor) rawStorage;
		SkyLightSectionStorageInvoker sky = (SkyLightSectionStorageInvoker) rawStorage;
		List<Object> record = dry ? new ArrayList<>() : null;
		LongArrayList entries = dry ? new LongArrayList() : null;
		if (dry) {
			record.add(entries);
		}

		long zeroNode = SectionPos.getZeroNode(pos.x(), pos.z());
		if (!dry) {
			storage.mcrs$setLightEnabled(zeroNode, true);
		}
		ChunkSkyLightSources sources = Objects.requireNonNullElse(this.getChunkSources(pos.x(), pos.z()), this.emptyChunkSources);
		ChunkSkyLightSources northSources = Objects.requireNonNullElse(this.getChunkSources(pos.x(), pos.z() - 1), this.emptyChunkSources);
		ChunkSkyLightSources southSources = Objects.requireNonNullElse(this.getChunkSources(pos.x(), pos.z() + 1), this.emptyChunkSources);
		ChunkSkyLightSources westSources = Objects.requireNonNullElse(this.getChunkSources(pos.x() - 1, pos.z()), this.emptyChunkSources);
		ChunkSkyLightSources eastSources = Objects.requireNonNullElse(this.getChunkSources(pos.x() + 1, pos.z()), this.emptyChunkSources);

		// Per column (index z * 16 + x): lowest source y and the four neighbor values.
		int[] low = new int[256];
		int[] north = new int[256];
		int[] south = new int[256];
		int[] west = new int[256];
		int[] east = new int[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				low[i] = sources.getLowestSourceY(x, z);
				north[i] = z == 0 ? northSources.getLowestSourceY(x, 15) : sources.getLowestSourceY(x, z - 1);
				south[i] = z == 15 ? southSources.getLowestSourceY(x, 0) : sources.getLowestSourceY(x, z + 1);
				west[i] = x == 0 ? westSources.getLowestSourceY(15, z) : sources.getLowestSourceY(x - 1, z);
				east[i] = x == 15 ? eastSources.getLowestSourceY(0, z) : sources.getLowestSourceY(x + 1, z);
			}
		}

		int topSectionY = sky.mcrs$getTopSectionY(zeroNode);
		int bottomSectionY = sky.mcrs$getBottomSectionY();
		int sectionMinX = SectionPos.sectionToBlockCoord(pos.x());
		int sectionMinZ = SectionPos.sectionToBlockCoord(pos.z());

		for (int sectionY = topSectionY - 1; sectionY >= bottomSectionY; sectionY--) {
			long sectionNode = SectionPos.asLong(pos.x(), sectionY, pos.z());
			DataLayer dataLayer;
			if (dry) {
				DataLayer current = storage.mcrs$updating().getLayer(sectionNode);
				dataLayer = current == null ? null : current.copy();
			} else {
				dataLayer = storage.mcrs$getDataLayerToWrite(sectionNode);
			}
			if (dataLayer == null) {
				continue;
			}
			int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
			int sectionMaxY = sectionMinY + 15;
			boolean sourcesBelow = false;
			boolean anyWrite = false;
			boolean allColumns = true;
			int fullRowsFrom = sectionMinY;
			for (int i = 0; i < 256; i++) {
				if (low[i] <= sectionMaxY) {
					anyWrite = true;
					fullRowsFrom = Math.max(fullRowsFrom, Math.max(sectionMinY, low[i]));
					if (low[i] < sectionMinY) {
						sourcesBelow = true;
					}
				} else {
					allColumns = false;
				}
			}

			if (anyWrite) {
				byte[] data = dataLayer.getData();
				for (int ly = 0; ly < 16; ly++) {
					int y = sectionMinY + ly;
					if (allColumns && y >= fullRowsFrom) {
						Arrays.fill(data, ly << 7, (ly << 7) + 128, (byte) -1);
						continue;
					}
					for (int i = 0; i < 256; i++) {
						if (low[i] <= y) {
							int index = ly << 8 | i;
							data[index >> 1] |= (byte) (15 << ((index & 1) << 2));
						}
					}
				}

				// Queue entries in vanilla's order: z, x, then y from the top down. Vanilla
				// enqueues where y == lowest or y < any neighbor's lowest.
				for (int i = 0; i < 256; i++) {
					int lowest = low[i];
					if (lowest > sectionMaxY) {
						continue;
					}
					int neighborLowest = Math.max(Math.max(north[i], south[i]), Math.max(west[i], east[i]));
					int start = Math.max(sectionMinY, lowest);
					long upper = Math.min((long) sectionMaxY, (long) neighborLowest - 1);
					for (long yl = upper; yl >= start; yl--) {
						mcrs$enqueue(engine, entries, sectionMinX + (i & 15), (int) yl, sectionMinZ + (i >> 4), i, lowest, north, south, west, east);
					}
					if (lowest >= sectionMinY && lowest > upper) {
						mcrs$enqueue(engine, entries, sectionMinX + (i & 15), lowest, sectionMinZ + (i >> 4), i, lowest, north, south, west, east);
					}
				}
			}

			if (dry) {
				record.add(sectionNode);
				record.add(dataLayer);
			}

			if (!sourcesBelow) {
				break;
			}
		}
		return record;
	}

	@Unique
	private static void mcrs$enqueue(LightEngineAccessor engine, @Nullable LongArrayList entries, int x, int y, int z, int i, int lowest,
			int[] north, int[] south, int[] west, int[] east) {
		long blockNode = BlockPos.asLong(x, y, z);
		long data = LightEngine.QueueEntry.increaseSkySourceInDirections(y == lowest, y < north[i], y < south[i], y < west[i], y < east[i]);
		if (entries != null) {
			entries.add(blockNode);
			entries.add(data);
		} else {
			engine.mcrs$enqueueIncrease(blockNode, data);
		}
	}
}
