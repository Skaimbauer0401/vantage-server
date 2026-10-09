package dev.mcrs.bridge;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Runs the FEATURES generation step on the worker pool instead of vanilla's single
 * "worldgen" consecutive executor.
 *
 * <p>The step writes blocks within a 3x3 chunk area around its chunk. Vanilla gets
 * mutual exclusion for free by running one step at a time; here a per-level region
 * lock grants a step only when no running step touches any of its 9 chunks. Steps
 * whose areas don't overlap run in parallel. Vanilla's chunk dependencies (terrain
 * of all 9 chunks done before, light of them only after) are unchanged.
 *
 * <p>Structures span many chunks and their pieces can carry placement state, so the
 * lock also covers every structure start placed in the chunk: chunks of the same
 * structure never place in parallel.
 *
 * <p>Which of two overlapping steps runs first already depends on timing in vanilla
 * (async terrain completion feeds the queue), so any order the lock produces is one
 * vanilla can produce too.
 *
 * <p>{@code -Dmcrs.parallelFeatures=false} restores vanilla's serial behavior.
 */
public final class ParallelFeatures {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.parallelFeatures"));

	/** Set while the original (vanilla) step body runs on this thread. */
	public static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

	private static final Map<ServerLevel, RegionLock> LOCKS = new WeakHashMap<>();

	private ParallelFeatures() {
	}

	private static synchronized RegionLock lock(ServerLevel level) {
		return LOCKS.computeIfAbsent(level, l -> new RegionLock());
	}

	/**
	 * Runs {@code step} on {@code executor} once the 3x3 area around {@code pos} and all
	 * {@code structureStarts} (start chunk positions) are free.
	 */
	public static <T> CompletableFuture<T> run(ServerLevel level, ChunkPos pos, long[] structureStarts, Executor executor, Supplier<T> step) {
		RegionLock lock = lock(level);
		return lock.acquire(pos.x(), pos.z(), structureStarts).thenApplyAsync(ignored -> {
			BYPASS.set(true);
			try {
				return step.get();
			} finally {
				BYPASS.set(false);
				lock.release(pos.x(), pos.z(), structureStarts);
			}
		}, executor);
	}

	/** Start chunk positions of every structure referenced by (so placed in) {@code chunk}. */
	public static long[] structureStarts(net.minecraft.world.level.chunk.ChunkAccess chunk) {
		LongOpenHashSet starts = new LongOpenHashSet();
		for (it.unimi.dsi.fastutil.longs.LongSet refs : chunk.getAllReferences().values()) {
			starts.addAll(refs);
		}
		return starts.toLongArray();
	}

	/**
	 * Grants 3x3 chunk areas plus sets of structure starts so that granted requests never
	 * share a chunk or a structure. Waiters are granted in arrival order unless blocked.
	 */
	static final class RegionLock {
		private final LongOpenHashSet busy = new LongOpenHashSet();
		private final LongOpenHashSet busyStarts = new LongOpenHashSet();
		private final ArrayDeque<Waiter> waiting = new ArrayDeque<>();

		private record Waiter(int x, int z, long[] starts, CompletableFuture<Void> granted) {
		}

		synchronized CompletableFuture<Void> acquire(int x, int z, long[] starts) {
			CompletableFuture<Void> granted = new CompletableFuture<>();
			if (waiting.isEmpty() && free(x, z, starts)) {
				mark(x, z, starts, true);
				granted.complete(null);
			} else {
				waiting.add(new Waiter(x, z, starts, granted));
			}
			return granted;
		}

		void release(int x, int z, long[] starts) {
			java.util.List<CompletableFuture<Void>> toGrant = new java.util.ArrayList<>();
			synchronized (this) {
				mark(x, z, starts, false);
				// Grant in arrival order; a blocked waiter reserves what it needs so later
				// waiters can't overtake it on the same chunks or structures.
				LongOpenHashSet blocked = new LongOpenHashSet();
				LongOpenHashSet blockedStarts = new LongOpenHashSet();
				for (Iterator<Waiter> it = waiting.iterator(); it.hasNext(); ) {
					Waiter w = it.next();
					if (free(w.x, w.z, w.starts) && !overlaps(blocked, w.x, w.z) && !anyIn(blockedStarts, w.starts)) {
						mark(w.x, w.z, w.starts, true);
						it.remove();
						toGrant.add(w.granted);
					} else {
						for (int dx = -1; dx <= 1; dx++) {
							for (int dz = -1; dz <= 1; dz++) {
								blocked.add(ChunkPos.pack(w.x + dx, w.z + dz));
							}
						}
						for (long s : w.starts) {
							blockedStarts.add(s);
						}
					}
				}
			}
			// Complete outside the monitor: completion triggers the step's async submit.
			toGrant.forEach(f -> f.complete(null));
		}

		private static boolean anyIn(LongOpenHashSet set, long[] keys) {
			for (long k : keys) {
				if (set.contains(k)) {
					return true;
				}
			}
			return false;
		}

		private boolean free(int x, int z, long[] starts) {
			return !overlaps(busy, x, z) && !anyIn(busyStarts, starts);
		}

		private static boolean overlaps(LongOpenHashSet cells, int x, int z) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (cells.contains(ChunkPos.pack(x + dx, z + dz))) {
						return true;
					}
				}
			}
			return false;
		}

		private void mark(int x, int z, long[] starts, boolean value) {
			for (long s : starts) {
				if (value) {
					busyStarts.add(s);
				} else {
					busyStarts.remove(s);
				}
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					long key = ChunkPos.pack(x + dx, z + dz);
					if (value) {
						busy.add(key);
					} else {
						busy.remove(key);
					}
				}
			}
		}
	}
}
