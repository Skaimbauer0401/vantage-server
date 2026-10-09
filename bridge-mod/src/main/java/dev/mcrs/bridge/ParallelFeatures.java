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

	/** Runs {@code step} on {@code executor} once the 3x3 area around {@code pos} is free. */
	public static <T> CompletableFuture<T> run(ServerLevel level, ChunkPos pos, Executor executor, Supplier<T> step) {
		RegionLock lock = lock(level);
		return lock.acquire(pos.x(), pos.z()).thenApplyAsync(ignored -> {
			BYPASS.set(true);
			try {
				return step.get();
			} finally {
				BYPASS.set(false);
				lock.release(pos.x(), pos.z());
			}
		}, executor);
	}

	/** Grants 3x3 chunk areas so that granted areas never overlap. Waiters are FIFO-fair per area. */
	static final class RegionLock {
		private final LongOpenHashSet busy = new LongOpenHashSet();
		private final ArrayDeque<Waiter> waiting = new ArrayDeque<>();

		private record Waiter(int x, int z, CompletableFuture<Void> granted) {
		}

		synchronized CompletableFuture<Void> acquire(int x, int z) {
			CompletableFuture<Void> granted = new CompletableFuture<>();
			if (waiting.isEmpty() && free(x, z)) {
				mark(x, z, true);
				granted.complete(null);
			} else {
				waiting.add(new Waiter(x, z, granted));
			}
			return granted;
		}

		void release(int x, int z) {
			java.util.List<CompletableFuture<Void>> toGrant = new java.util.ArrayList<>();
			synchronized (this) {
				mark(x, z, false);
				// Grant in arrival order; a waiter blocked by a busy area doesn't block
				// later waiters whose areas are free and don't overlap it.
				LongOpenHashSet blocked = new LongOpenHashSet();
				for (Iterator<Waiter> it = waiting.iterator(); it.hasNext(); ) {
					Waiter w = it.next();
					if (free(w.x, w.z) && !overlaps(blocked, w.x, w.z)) {
						mark(w.x, w.z, true);
						it.remove();
						toGrant.add(w.granted);
					} else {
						for (int dx = -1; dx <= 1; dx++) {
							for (int dz = -1; dz <= 1; dz++) {
								blocked.add(ChunkPos.pack(w.x + dx, w.z + dz));
							}
						}
					}
				}
			}
			// Complete outside the monitor: completion triggers the step's async submit.
			toGrant.forEach(f -> f.complete(null));
		}

		private boolean free(int x, int z) {
			return !overlaps(busy, x, z);
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

		private void mark(int x, int z, boolean value) {
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
