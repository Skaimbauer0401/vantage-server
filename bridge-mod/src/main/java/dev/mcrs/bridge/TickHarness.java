package dev.mcrs.bridge;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Reproducible tick runs for checking that game logic optimizations stay exactly vanilla,
 * and for measuring them.
 *
 * <p>{@code /mcrs harness <scenario> <size> <ticks> <hash>} builds a scenario in a fresh,
 * force-loaded area at 0 0 of a flat world, puts the world into a fixed state (random seeds,
 * game time, day time, weather), then runs {@code ticks} ticks. With {@code hash} it hashes,
 * after every tick, the saved data of every entity in iteration order and the sequence of
 * block changes made during the tick, and writes them to {@code harness-<scenario>.txt};
 * two runs are the same game if these files are equal. Without it, it only measures tick
 * time.
 *
 * <p>Needs {@code -Dmcrs.deterministic=<seed>}, which makes every random source created on
 * the server thread seeded from a counter (see {@code mixin.RandomSupportMixin}).
 */
public final class TickHarness {
	public static final boolean DETERMINISTIC = System.getProperty("mcrs.deterministic") != null;
	public static final long SEED = Long.getLong("mcrs.deterministic", 0L);

	/** Force-loaded radius; scenarios use the inner {@link #TICKING_RADIUS_CHUNKS}, which tick entities. */
	private static final int RADIUS_CHUNKS = 6;
	/** First of two ticks whose full entity data is written ({@code -Dmcrs.harness.dump}). */
	private static final int DUMP_FROM = Integer.getInteger("mcrs.harness.dump", 0);
	private static final int DUMP_TICKS = Integer.getInteger("mcrs.harness.dumpTicks", 2);
	private static final int TICKING_RADIUS_CHUNKS = 4;
	private static final int SURFACE_Y = -60;
	private static final ThreadLocal<long[]> SEED_COUNTER = ThreadLocal.withInitial(() -> new long[1]);

	private static volatile Run run;
	/** Block change hash of the current tick (server thread only). */
	private static long blockHash;
	private static long blockChanges;

	private TickHarness() {
	}

	/** {@code RandomSupport.generateUniqueSeed} in deterministic mode: per thread, a counter. */
	public static long uniqueSeed() {
		long[] counter = SEED_COUNTER.get();
		long n = counter[0]++;
		return mix(SEED ^ Thread.currentThread().getName().hashCode() * 0x9E3779B97F4A7C15L ^ n * 0xC2B2AE3D27D4EB4FL);
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Called from {@code mixin.LevelChunkHarnessMixin} for every block change. */
	public static void onBlockChange(BlockPos pos, BlockState state) {
		if (run != null && run.hash && Thread.currentThread() == run.thread) {
			blockHash = blockHash * 1000003L + mix(pos.asLong() * 31 + Block.getId(state));
			blockChanges++;
		}
	}

	public static int start(CommandSourceStack source, String scenario, int size, int ticks, boolean hash) {
		if (!DETERMINISTIC) {
			source.sendFailure(Component.literal("Start the server with -Dmcrs.deterministic=<seed>"));
			return 0;
		}
		if (run != null || pending != null) {
			source.sendFailure(Component.literal("A harness run is already active"));
			return 0;
		}
		MinecraftServer server = source.getServer();
		ServerLevel level = server.overworld();
		ServerChunkCache cache = level.getChunkSource();
		ChunkPos center = new ChunkPos(0, 0);
		report(source, String.format(Locale.ROOT, "[harness] %s size %d: loading area...", scenario, size));
		cache.addTicketAndLoadWithRadius(TicketType.FORCED, center, RADIUS_CHUNKS).whenComplete((ignored, error) -> server.execute(() -> {
			if (error != null) {
				report(source, "[harness] failed: " + error);
				return;
			}
			// Entity sections load asynchronously after the chunks; build once all are ticking.
			int[] waited = {0};
			pending = () -> {
				for (int dx = -TICKING_RADIUS_CHUNKS; dx <= TICKING_RADIUS_CHUNKS; dx++) {
					for (int dz = -TICKING_RADIUS_CHUNKS; dz <= TICKING_RADIUS_CHUNKS; dz++) {
						if (!level.areEntitiesActuallyLoadedAndTicking(new ChunkPos(dx, dz))) {
							if (++waited[0] == 600) {
								report(source, "[harness] still waiting for entity ticking at chunk " + dx + " " + dz);
							}
							return false;
						}
					}
				}
				begin(source, server, level, cache, center, scenario, size, ticks, hash);
				return true;
			};
		}));
		return 1;
	}

	private static volatile java.util.function.BooleanSupplier pending;

	private static void begin(CommandSourceStack source, MinecraftServer server, ServerLevel level, ServerChunkCache cache, ChunkPos center,
			String scenario, int size, int ticks, boolean hash) {
		{
			CommandSourceStack quiet = source.withLevel(level).withSuppressedOutput();
			// A fixed world state, whatever happened before this command.
			command(server, quiet, "kill @e[type=!minecraft:player]");
			command(server, quiet, "weather clear 1000000");
			command(server, quiet, "time set 1000");
			((ServerLevelData) level.getLevelData()).setGameTime(100_000L);
			for (ServerLevel l : server.getAllLevels()) {
				((dev.mcrs.bridge.mixin.LevelRandomAccessor) l).mcrs$random().setSeed(SEED ^ l.dimension().identifier().hashCode());
				((dev.mcrs.bridge.mixin.LevelRandomAccessor) l).mcrs$setRandValue((int) mix(SEED + l.dimension().identifier().hashCode()));
			}
			((dev.mcrs.bridge.mixin.MinecraftServerRandomAccessor) server).mcrs$random().setSeed(SEED);
			SEED_COUNTER.get()[0] = 0;
			String error2 = build(server, quiet, scenario, size);
			if (error2 != null) {
				report(source, "[harness] " + error2);
				cache.removeTicketWithRadius(TicketType.FORCED, center, RADIUS_CHUNKS);
				return;
			}
			int entities = 0;
			for (Entity e : level.getAllEntities()) {
				entities++;
			}
			report(source, String.format(Locale.ROOT, "[harness] identity hashes: %08x %08x %08x %08x",
					System.identityHashCode(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME),
					System.identityHashCode(net.minecraft.world.entity.schedule.Activity.WORK),
					System.identityHashCode(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH.value()),
					System.identityHashCode(net.minecraft.world.level.block.Blocks.STONE)));
			report(source, String.format(Locale.ROOT, "[harness] %s: %d entities, running %d ticks%s", scenario, entities, ticks,
					hash ? " with hashing" : ""));
			blockHash = 0;
			blockChanges = 0;
			run = new Run(source, cache, center, scenario, ticks, hash, Thread.currentThread());
			if (Boolean.getBoolean("mcrs.harness.watcher")) {
				// A player standing in the middle, for the editions that slow down distant mobs.
				command(server, quiet, "gamerule spawn_mobs false");
				FakePlayers.start(source, 1, 0.0, ticks, 0.0, 2);
			}
		}
	}

	/** Called after every server tick with its duration. */
	public static void onTick(long nanos) {
		java.util.function.BooleanSupplier waiting = pending;
		if (waiting != null && waiting.getAsBoolean()) {
			pending = null;
			return;
		}
		Run r = run;
		if (r == null) {
			return;
		}
		if (r.record(nanos)) {
			run = null;
			r.finish();
		}
	}

	private static final class Run {
		final CommandSourceStack source;
		final ServerChunkCache cache;
		final ChunkPos center;
		final String scenario;
		final boolean hash;
		final Thread thread;
		final long[] nanos;
		final List<String> lines = new ArrayList<>();
		/** Full entity data of the first ticks, to find the cause when runs differ. */
		final List<String> dump = new ArrayList<>();
		long combined = 17;
		int tick;

		Run(CommandSourceStack source, ServerChunkCache cache, ChunkPos center, String scenario, int ticks, boolean hash, Thread thread) {
			this.source = source;
			this.cache = cache;
			this.center = center;
			this.scenario = scenario;
			this.hash = hash;
			this.thread = thread;
			this.nanos = new long[ticks];
		}

		boolean record(long tickNanos) {
			nanos[tick] = tickNanos;
			if (hash) {
				ServerLevel level = source.getServer().overworld();
				long entityHash = 0;
				int count = 0;
				for (Entity entity : level.getAllEntities()) {
					if (entity instanceof Player) {
						continue;
					}
					TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
					entity.saveWithoutId(output);
					CompoundTag tag = output.buildResult();
					entityHash = entityHash * 1000003L + mix(entity.getType().getDescriptionId().hashCode() * 31L + canonicalHash(tag, ""));
					if (tick >= DUMP_FROM && tick < DUMP_FROM + DUMP_TICKS) {
						dump.add(tick + " " + entity.getType() + " " + tag);
					}
					count++;
				}
				// Probe: identity hash of a new object (under -XX:hashCode=3, a global sequence).
				lines.add(String.format(Locale.ROOT, "%d %d %016x %d %016x %08x", tick, count, entityHash, blockChanges, blockHash,
						System.identityHashCode(new Object())));
				combined = mix(combined * 31 + entityHash) ^ blockHash;
				blockHash = 0;
				blockChanges = 0;
			}
			tick++;
			return tick >= nanos.length;
		}

		void finish() {
			long[] sorted = nanos.clone();
			Arrays.sort(sorted);
			double mean = Arrays.stream(nanos).average().orElse(0) / 1e6;
			String summary = String.format(Locale.ROOT, "[harness] %s: %d ticks, mean %.3f ms, median %.3f ms, p95 %.3f ms%s", scenario,
					nanos.length, mean, sorted[sorted.length / 2] / 1e6, sorted[(int) (sorted.length * 0.95)] / 1e6,
					hash ? String.format(Locale.ROOT, ", state hash %016x", combined) : "");
			report(source, summary);
			if (hash) {
				Path file = Path.of("harness-" + scenario + ".txt");
				try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					for (String line : lines) {
						out.write(line);
						out.write('\n');
					}
				} catch (IOException e) {
					report(source, "[harness] could not write " + file + ": " + e);
				}
				try {
					Files.write(Path.of("harness-" + scenario + "-entities.txt"), dump, StandardCharsets.UTF_8);
				} catch (IOException e) {
					report(source, "[harness] could not write the entity dump: " + e);
				}
			}
			cache.removeTicketWithRadius(TicketType.FORCED, center, RADIUS_CHUNKS);
		}
	}

	/** Lists vanilla fills from hash maps with run-dependent order: compared as sets. */
	private static final java.util.Set<String> UNORDERED_LISTS = java.util.Set.of("attributes", "modifiers");

	/** Hash of a saved entity: compound keys in any order, the lists above in any order. */
	private static long canonicalHash(net.minecraft.nbt.Tag tag, String key) {
		if (tag instanceof CompoundTag compound) {
			long h = 0x51;
			for (String k : compound.keySet()) {
				h += mix(k.hashCode() * 0x9E3779B97F4A7C15L + canonicalHash(compound.get(k), k));
			}
			return h;
		}
		if (tag instanceof net.minecraft.nbt.ListTag list) {
			boolean unordered = UNORDERED_LISTS.contains(key);
			long h = 0x73;
			for (int i = 0; i < list.size(); i++) {
				long e = canonicalHash(list.get(i), "");
				h = unordered ? h + mix(e) : h * 1000003L + e;
			}
			return h;
		}
		return tag.hashCode();
	}

	private static String build(MinecraftServer server, CommandSourceStack s, String scenario, int size) {
		int y = SURFACE_Y;
		Random random = new Random(SEED);
		switch (scenario) {
			case "mobs" -> {
				// A 48x48 pen of mixed mobs, villagers and items.
				int half = 24;
				command(server, s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_fence", -half, y, -half, half, y, -half));
				command(server, s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_fence", -half, y, half, half, y, half));
				command(server, s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_fence", -half, y, -half, -half, y, half));
				command(server, s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_fence", half, y, -half, half, y, half));
				String[] types = {"cow", "sheep", "pig", "chicken", "villager", "zombie", "skeleton", "spider", "iron_golem", "rabbit", "horse", "wolf"};
				for (int i = 0; i < size; i++) {
					int x = random.nextInt(2 * half - 4) - half + 2;
					int z = random.nextInt(2 * half - 4) - half + 2;
					command(server, s, String.format(Locale.ROOT, "summon minecraft:%s %d %d %d {PersistenceRequired:1b}", types[i % types.length], x, y, z));
				}
				// Entities others collide with (and that collide with others).
				String[] solid = {"oak_boat", "minecart", "shulker"};
				for (int i = 0; i < size / 30; i++) {
					int x = random.nextInt(2 * half - 4) - half + 2;
					int z = random.nextInt(2 * half - 4) - half + 2;
					command(server, s, String.format(Locale.ROOT, "summon minecraft:%s %d %d %d", solid[i % solid.length], x, y, z));
				}
				for (int i = 0; i < size / 10; i++) {
					int x = random.nextInt(2 * half - 4) - half + 2;
					int z = random.nextInt(2 * half - 4) - half + 2;
					command(server, s, String.format(Locale.ROOT, "summon minecraft:item %d %d %d {Item:{id:\"minecraft:wheat\",count:1}}", x, y + 1, z));
				}
			}
			case "farm" -> {
				// Random tick targets: wheat on farmland, dirt next to grass, saplings; random
				// ticks sped up by "size" times.
				command(server, s, "gamerule random_tick_speed " + 3 * size);
				command(server, s, "gamerule randomTickSpeed " + 3 * size);
				command(server, s, String.format(Locale.ROOT, "fill -48 %d -48 48 %d -17 minecraft:farmland", y - 1, y - 1));
				command(server, s, String.format(Locale.ROOT, "fill -48 %d -48 48 %d -17 minecraft:wheat", y, y));
				command(server, s, String.format(Locale.ROOT, "fill -48 %d -16 48 %d 15 minecraft:dirt", y - 1, y - 1));
				for (int x = -48; x <= 48; x += 6) {
					command(server, s, String.format(Locale.ROOT, "fill %d %d -16 %d %d 15 minecraft:grass_block", x, y - 1, x, y - 1));
				}
				command(server, s, String.format(Locale.ROOT, "fill -48 %d 16 48 %d 47 minecraft:dirt", y - 1, y - 1));
				for (int x = -48; x <= 48; x += 4) {
					for (int z = 16; z <= 47; z += 4) {
						command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:oak_sapling", x, y, z));
					}
				}
			}
			case "redstone" -> {
				// Units of: observer clock -> 12 dust -> sticky piston pushing a block, in a
				// grid of 8 rows (up to 320 units inside the loaded area).
				if (size > 320) {
					return "redstone size is at most 320";
				}
				for (int i = 0; i < size; i++) {
					int x = -60 + (i / 8) * 3;
					int z = -60 + (i % 8) * 16;
					command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:observer[facing=south]", x, y, z));
					command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:observer[facing=north]", x, y, z + 1));
					for (int dz = 2; dz <= 13; dz++) {
						command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_wire", x, y, z + dz));
					}
					command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:sticky_piston[facing=east]", x, y, z + 14));
					command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 1, y, z + 14));
					command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_lamp", x, y, z + 15));
				}
			}
			case "hoppers" -> {
				// Hoppers as in bases: a quarter move items from a full chest into a chest below,
				// the rest wait idle under air or under an empty chest.
				if (size > 1600) {
					return "hoppers size is at most 1600";
				}
				for (int i = 0; i < size; i++) {
					int x = -60 + (i % 40) * 3;
					int z = -60 + (i / 40) * 3;
					switch (i % 4) {
						case 0 -> {
							command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest", x, y, z));
							command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:hopper[facing=down]", x, y + 1, z));
							command(server, s, String.format(Locale.ROOT,
									"setblock %d %d %d minecraft:chest{Items:[{Slot:0b,id:\"minecraft:cobblestone\",count:64},{Slot:1b,id:\"minecraft:dirt\",count:64}]}", x, y + 2, z));
						}
						case 1 -> command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:hopper[facing=down]", x, y, z));
						default -> {
							command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:hopper[facing=down]", x, y, z));
							command(server, s, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest", x, y + 1, z));
						}
					}
				}
			}
			default -> {
				return "unknown scenario " + scenario + " (mobs, redstone, farm, hoppers)";
			}
		}
		return null;
	}

	private static void command(MinecraftServer server, CommandSourceStack source, String command) {
		try {
			server.getCommands().getDispatcher().execute(command, source);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			McrsBridge.LOGGER.warn("[harness] command failed: {}: {}", command, e.getMessage());
		}
	}

	private static void report(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message), true);
		McrsBridge.LOGGER.info(message);
	}
}
