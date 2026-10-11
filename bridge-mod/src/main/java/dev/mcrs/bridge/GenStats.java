package dev.mcrs.bridge;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * {@code /mcrs genstats}: where generation time goes since the previous call. CPU time per
 * thread group (including Rust code called from those threads) and busy time of vanilla's
 * consecutive executors ("worldgen", "light", ...), which run one task at a time.
 */
public final class GenStats {
	static final Map<String, LongAdder> LANE_NANOS = new ConcurrentHashMap<>();
	static final Map<String, LongAdder> LANE_TASKS = new ConcurrentHashMap<>();
	private static long lastWall = System.nanoTime();
	private static final Map<Long, Long> LAST_CPU = new ConcurrentHashMap<>();

	private GenStats() {
	}

	public static void lane(String name, long nanos) {
		LANE_NANOS.computeIfAbsent(name, k -> new LongAdder()).add(nanos);
		LANE_TASKS.computeIfAbsent(name, k -> new LongAdder()).increment();
	}

	public static String report() {
		long now = System.nanoTime();
		double wall = (now - lastWall) / 1e9;
		lastWall = now;
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		Map<String, double[]> groups = new TreeMap<>();
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			long cpu = threads.getThreadCpuTime(t.threadId());
			if (cpu < 0) {
				continue;
			}
			Long before = LAST_CPU.put(t.threadId(), cpu);
			double seconds = (cpu - (before == null ? 0 : before)) / 1e9;
			String group = t.getName().replaceAll("[0-9]+", "#");
			double[] g = groups.computeIfAbsent(group, k -> new double[2]);
			g[0] += seconds;
			g[1]++;
		}
		StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "[genstats] %.1f s:", wall));
		groups.entrySet().stream().filter(e -> e.getValue()[0] / wall >= 0.05).sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
				.forEach(e -> out.append(String.format(Locale.ROOT, " %s x%d %.2f cores;", e.getKey(), (int) e.getValue()[1], e.getValue()[0] / wall)));
		out.append(" lanes busy:");
		new TreeMap<>(LANE_NANOS).forEach((name, nanos) -> out.append(String.format(Locale.ROOT, " %s %.0f%% (%d tasks)", name, 100 * nanos.sumThenReset() / 1e9 / wall,
				LANE_TASKS.get(name).sumThenReset())));
		int filled = (int) (TerrainFill.FILLED.getAndSet(0) + TerrainFill.SKIPPED.getAndSet(0));
		out.append("; per filled chunk ").append(StageTimes.summary(filled));
		out.append(String.format(Locale.ROOT, "; biomes by Rust: %d chunks", Biomes.FILLED.getAndSet(0)));
		if (Biomes.VERIFY) {
			out.append(String.format(Locale.ROOT, " (verify: %d identical, %d ties, %d differ)", Biomes.VERIFIED.get(), Biomes.TIES.get(), Biomes.MISMATCHED.get()));
		}
		if (ParallelSave.ENABLED) {
			out.append(String.format(Locale.ROOT, "; chunks written pre-encoded: %d", ParallelSave.USED.getAndSet(0)));
			if (ParallelSave.VERIFY) {
				out.append(String.format(Locale.ROOT, " (verify: %d identical, %d differ)", ParallelSave.VERIFIED.get(), ParallelSave.MISMATCHED.get()));
			}
		}
		return out.toString();
	}
}
