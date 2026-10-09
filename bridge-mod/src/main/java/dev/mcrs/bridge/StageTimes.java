package dev.mcrs.bridge;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wall time per world generation stage, summed over all worker threads, for the chunk
 * benchmark. Run it with fewer workers than cores (e.g. {@code -Dmax.bg.threads=6}) so
 * threads aren't descheduled and wall time is CPU time.
 */
public final class StageTimes {
	public static final String[] NAMES = {"biomes", "noise chunk", "terrain fill (Rust)", "carvers", "heightmaps", "features", "ores", "structure starts",
			"structure refs", "spawn"};
	public static final int BIOMES = 0;
	public static final int NOISE_CHUNK = 1;
	public static final int FILL = 2;
	public static final int CARVERS = 3;
	public static final int HEIGHTMAPS = 4;
	public static final int FEATURES = 5;
	public static final int ORES = 6;
	public static final int STARTS = 7;
	public static final int REFS = 8;
	public static final int SPAWN = 9;

	private static final LongAdder[] NANOS = new LongAdder[NAMES.length];
	private static final ThreadLocal<long[]> STARTS_AT = ThreadLocal.withInitial(() -> new long[NAMES.length]);

	static {
		for (int i = 0; i < NANOS.length; i++) {
			NANOS[i] = new LongAdder();
		}
	}

	private StageTimes() {
	}

	public static void begin(int stage) {
		STARTS_AT.get()[stage] = System.nanoTime();
	}

	public static void end(int stage) {
		NANOS[stage].add(System.nanoTime() - STARTS_AT.get()[stage]);
	}

	/** {@code stage: ms per chunk} for every stage, then resets. */
	public static String summary(int chunks) {
		StringBuilder out = new StringBuilder("stage ms/chunk:");
		double total = 0;
		for (int i = 0; i < NAMES.length; i++) {
			double ms = NANOS[i].sumThenReset() / 1e6 / Math.max(chunks, 1);
			if (i != ORES) {
				total += ms;
			}
			out.append(String.format(Locale.ROOT, " %s %.2f,", NAMES[i], ms));
		}
		out.append(String.format(Locale.ROOT, " total %.2f (ores are part of features)", total));
		return out.toString();
	}
}
