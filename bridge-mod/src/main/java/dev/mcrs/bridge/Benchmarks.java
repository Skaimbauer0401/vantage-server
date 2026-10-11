package dev.mcrs.bridge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Reproducible performance measurements. These numbers are the baseline every Rust
 * subsystem has to beat.
 *
 * <pre>
 * /mcrs bench chunks &lt;radius&gt; [x z]               load/generate a square of chunks, report chunks/s
 * /mcrs bench entities &lt;type&gt; &lt;count&gt; [seconds]   spawn mobs, report MSPT
 * </pre>
 * Without a center, chunks picks an ungenerated area (pure generation). Run it
 * again with the printed center after a restart to measure loading from disk.
 */
public final class Benchmarks {
	private static final String TAG = "mcrs_bench";
	private static final int WARMUP_TICKS = 100;
	private static final int ENTITY_AREA_RADIUS_CHUNKS = 4;
	private static final int ENTITY_SPREAD_BLOCKS = 24;

	/** Nanoseconds spent in ThreadedLevelLightEngine.runUpdate (serial per dimension). */
	public static final java.util.concurrent.atomic.AtomicLong LIGHT_NANOS = new java.util.concurrent.atomic.AtomicLong();


	private static boolean running;

	/** Samples the worker pool every 2 ms while a chunk benchmark runs. */
	private static final class PoolSampler extends Thread {
		volatile boolean stop;
		long samples;
		long running;
		long active;
		long queued;
		long idleWithoutWork;

		PoolSampler() {
			super("mcrs-pool-sampler");
			setDaemon(true);
		}

		@Override
		public void run() {
			java.util.concurrent.ForkJoinPool pool = null;
			while (!stop) {
				if (pool == null) {
					for (Thread t : Thread.getAllStackTraces().keySet()) {
						if (t instanceof java.util.concurrent.ForkJoinWorkerThread w && t.getName().startsWith("Worker-Main")) {
							pool = w.getPool();
							break;
						}
					}
				}
				if (pool != null) {
					samples++;
					int r = pool.getRunningThreadCount();
					running += r;
					active += pool.getActiveThreadCount();
					long q = pool.getQueuedSubmissionCount() + pool.getQueuedTaskCount();
					queued += q;
					if (q == 0 && r < pool.getParallelism()) {
						idleWithoutWork++;
					}
				}
				try {
					Thread.sleep(2);
				} catch (InterruptedException e) {
					return;
				}
			}
		}

		String summary() {
			if (samples == 0) {
				return "pool: no samples";
			}
			return String.format(Locale.ROOT, "workers running avg %.1f, active avg %.1f, queued tasks avg %.1f, no queued work %.0f%% of the time",
					(double) running / samples, (double) active / samples, (double) queued / samples, 100.0 * idleWithoutWork / samples);
		}
	}
	private static EntityBench entityBench;

	private Benchmarks() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mcrs")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("bench")
						.then(Commands.literal("chunks")
								.then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
										.executes(c -> chunks(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), null))
										.then(Commands.argument("x", IntegerArgumentType.integer())
												.then(Commands.argument("z", IntegerArgumentType.integer())
														.executes(c -> chunks(c.getSource(), IntegerArgumentType.getInteger(c, "radius"),
																new ChunkPos(IntegerArgumentType.getInteger(c, "x"),
																		IntegerArgumentType.getInteger(c, "z"))))))))
						.then(Commands.literal("entities")
								.then(Commands.argument("type", IdentifierArgument.id())
										.then(Commands.argument("count", IntegerArgumentType.integer(1, 20000))
												.executes(c -> entities(c.getSource(), IdentifierArgument.getId(c, "type").toString(),
														IntegerArgumentType.getInteger(c, "count"), 30))
												.then(Commands.argument("seconds", IntegerArgumentType.integer(5, 600))
														.executes(c -> entities(c.getSource(), IdentifierArgument.getId(c, "type").toString(),
																IntegerArgumentType.getInteger(c, "count"),
																IntegerArgumentType.getInteger(c, "seconds"))))))))
				.then(Commands.literal("genstats").executes(c -> {
					report(c.getSource(), GenStats.report());
					return 1;
				}))
				.then(Commands.literal("bots")
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
								.then(Commands.argument("speed", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 10.0))
										.then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
												.executes(c -> FakePlayers.start(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
														com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "speed"),
														IntegerArgumentType.getInteger(c, "ticks")))))))
				.then(Commands.literal("harness")
						.then(Commands.argument("scenario", com.mojang.brigadier.arguments.StringArgumentType.word())
								.then(Commands.argument("size", IntegerArgumentType.integer(1, 5000))
										.then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
												.then(Commands.argument("hash", com.mojang.brigadier.arguments.BoolArgumentType.bool())
														.executes(c -> TickHarness.start(c.getSource(),
																com.mojang.brigadier.arguments.StringArgumentType.getString(c, "scenario"),
																IntegerArgumentType.getInteger(c, "size"), IntegerArgumentType.getInteger(c, "ticks"),
																com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "hash")))))))));
	}

	private static ChunkPos freshArea() {
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		return new ChunkPos(rng.nextInt(50_000, 100_000) * (rng.nextBoolean() ? 1 : -1),
				rng.nextInt(50_000, 100_000) * (rng.nextBoolean() ? 1 : -1));
	}

	/** While true, the server must keep ticking even with no players online. */
	public static boolean isRunning() {
		// Harness test servers keep ticking without players too.
		return running || TickHarness.DETERMINISTIC || FakePlayers.isRunning();
	}

	private static boolean begin(CommandSourceStack source) {
		if (running) {
			source.sendFailure(Component.literal("A benchmark is already running"));
			return false;
		}
		running = true;
		return true;
	}

	static void report(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message), true);
		if (NativeBridge.isAvailable()) {
			NativeBridge.log(NativeBridge.INFO, message);
		}
	}

	private static int chunks(CommandSourceStack source, int radius, ChunkPos center) {
		if (!begin(source)) {
			return 0;
		}
		boolean generate = center == null;
		ChunkPos pos = generate ? freshArea() : center;
		ServerChunkCache cache = source.getLevel().getChunkSource();
		MinecraftServer server = source.getServer();
		report(source, String.format(Locale.ROOT, "[bench] chunks: %s radius %d around chunk %d %d...",
				generate ? "generating" : "loading", radius, pos.x(), pos.z()));

		long start = System.nanoTime();
		LIGHT_NANOS.set(0);

		PoolSampler sampler = new PoolSampler();
		sampler.start();
		cache.addTicketAndLoadWithRadius(TicketType.FORCED, pos, radius).whenComplete((ignored, error) -> server.execute(() -> {
			double seconds = (System.nanoTime() - start) / 1e9;
			int loaded = 0;
			for (int x = pos.x() - radius; x <= pos.x() + radius; x++) {
				for (int z = pos.z() - radius; z <= pos.z() + radius; z++) {
					if (cache.getChunkNow(x, z) != null) {
						loaded++;
					}
				}
			}
			cache.removeTicketWithRadius(TicketType.FORCED, pos, radius);
			running = false;
			if (error != null) {
				report(source, "[bench] chunks failed: " + error);
				return;
			}
			report(source, String.format(Locale.ROOT, "[bench] chunks: %d full chunks in %.2f s = %.1f chunks/s (center %d %d, %s)",
					loaded, seconds, loaded / seconds, pos.x(), pos.z(), generate ? "generated" : "loaded"));
			sampler.stop = true;
			report(source, "[bench] " + sampler.summary());
			report(source, String.format(Locale.ROOT, "[bench] light engine busy %.2f s of %.2f s (%.0f%%)",
					LIGHT_NANOS.get() / 1e9, seconds, 100 * LIGHT_NANOS.get() / 1e9 / seconds));
			report(source, String.format(Locale.ROOT, "[bench] terrain: %d filled by Rust, %d by vanilla%s",
					TerrainFill.FILLED.getAndSet(0), TerrainFill.SKIPPED.getAndSet(0),
					TerrainFill.VERIFY ? String.format(Locale.ROOT, ", verify: %d compared, %d differ",
							TerrainFill.VERIFIED.get(), TerrainFill.MISMATCHED.get()) : ""));
			report(source, "[bench] " + StageTimes.summary(loaded));
			if (FastLight.VERIFY) {
				report(source, String.format(Locale.ROOT, "[bench] fast light verify: %d checks %s, %d differ",
						FastLight.CHECKED.get(), FastLight.BY_KIND, FastLight.MISMATCHED.get()));
			}
			if (FastOres.VERIFY) {
				report(source, String.format(Locale.ROOT, "[bench] fast ores verify: %d placements identical, %d differ",
						FastOres.VERIFIED.get(), FastOres.MISMATCHED.get()));
			}
			if (ChunkIo.VERIFY) {
				report(source, String.format(Locale.ROOT, "[bench] chunk io verify: %d containers compared, %d mismatches",
						ChunkIo.VERIFIED.get(), ChunkIo.MISMATCHES.get()));
			}
		}));
		return 1;
	}

	private static int entities(CommandSourceStack source, String type, int count, int seconds) {
		if (!begin(source)) {
			return 0;
		}
		ChunkPos pos = freshArea();
		ServerLevel level = source.getLevel();
		ServerChunkCache cache = level.getChunkSource();
		MinecraftServer server = source.getServer();
		report(source, String.format(Locale.ROOT, "[bench] entities: preparing %d %s at chunk %d %d...", count, type, pos.x(), pos.z()));

		cache.addTicketAndLoadWithRadius(TicketType.FORCED, pos, ENTITY_AREA_RADIUS_CHUNKS).whenComplete((ignored, error) -> server.execute(() -> {
			if (error != null) {
				cleanupEntities(source, cache, pos);
				report(source, "[bench] entities failed: " + error);
				return;
			}
			CommandSourceStack quiet = source.withLevel(level).withSuppressedOutput();
			ThreadLocalRandom rng = ThreadLocalRandom.current();
			int cx = pos.getMiddleBlockX();
			int cz = pos.getMiddleBlockZ();
			for (int i = 0; i < count; i++) {
				int x = cx + rng.nextInt(-ENTITY_SPREAD_BLOCKS, ENTITY_SPREAD_BLOCKS + 1);
				int z = cz + rng.nextInt(-ENTITY_SPREAD_BLOCKS, ENTITY_SPREAD_BLOCKS + 1);
				run(server, quiet, String.format(Locale.ROOT, 
						"execute positioned %d 0 %d positioned over motion_blocking_no_leaves run summon %s ~ ~ ~ {Tags:[\"%s\"],PersistenceRequired:1b}",
						x, z, type, TAG));
			}
			int spawned = countBenchEntities(server, quiet);
			if (spawned == 0) {
				cleanupEntities(source, cache, pos);
				report(source, "[bench] entities: could not summon \"" + type + "\"");
				return;
			}
			entityBench = new EntityBench(source, cache, pos, spawned, seconds * 20);
		}));
		return 1;
	}

	private static int run(MinecraftServer server, CommandSourceStack source, String command) {
		try {
			return server.getCommands().getDispatcher().execute(command, source);
		} catch (CommandSyntaxException e) {
			return 0;
		}
	}

	private static int countBenchEntities(MinecraftServer server, CommandSourceStack source) {
		return run(server, source, "execute if entity @e[tag=" + TAG + "]");
	}

	private static void cleanupEntities(CommandSourceStack source, ServerChunkCache cache, ChunkPos pos) {
		run(source.getServer(), source.withSuppressedOutput(), "kill @e[tag=" + TAG + "]");
		cache.removeTicketWithRadius(TicketType.FORCED, pos, ENTITY_AREA_RADIUS_CHUNKS);
		running = false;
	}

	/** Called by {@link dev.mcrs.bridge.mixin.MinecraftServerMixin} after every tick. */
	public static void onTick(long nanos) {
		TickHarness.onTick(nanos);
		FakePlayers.onTick(nanos);
		EntityBench bench = entityBench;
		if (bench != null && bench.record(nanos)) {
			entityBench = null;
			bench.finish();
		}
	}

	private static final class EntityBench {
		private final CommandSourceStack source;
		private final ServerChunkCache cache;
		private final ChunkPos pos;
		private final int spawned;
		private final long[] samples;
		private int tick;

		EntityBench(CommandSourceStack source, ServerChunkCache cache, ChunkPos pos, int spawned, int ticks) {
			this.source = source;
			this.cache = cache;
			this.pos = pos;
			this.spawned = spawned;
			this.samples = new long[ticks];
			report(source, String.format(Locale.ROOT, "[bench] entities: %d spawned, warming up %d ticks, measuring %d ticks...",
					spawned, WARMUP_TICKS, ticks));
		}

		/** Returns true once all samples are recorded. */
		boolean record(long nanos) {
			int index = tick++ - WARMUP_TICKS;
			if (index >= 0) {
				samples[index] = nanos;
			}
			return index == samples.length - 1;
		}

		void finish() {
			int alive = countBenchEntities(source.getServer(), source.withSuppressedOutput());
			cleanupEntities(source, cache, pos);
			long[] sorted = samples.clone();
			Arrays.sort(sorted);
			double avg = Arrays.stream(sorted).average().orElse(0) / 1e6;
			report(source, String.format(Locale.ROOT, 
					"[bench] entities: %d spawned (%d alive at end) | MSPT avg %.2f p50 %.2f p95 %.2f max %.2f over %d ticks",
					spawned, alive, avg, sorted[sorted.length / 2] / 1e6, sorted[(int) (sorted.length * 0.95)] / 1e6,
					sorted[sorted.length - 1] / 1e6, sorted.length));
		}
	}
}
