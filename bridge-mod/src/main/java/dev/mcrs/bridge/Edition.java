package dev.mcrs.bridge;

import java.util.Locale;

/**
 * Which set of game behavior changes is on, chosen with {@code edition} in mcrs.toml (passed as
 * {@code -Dmcrs.edition}):
 * <ul>
 * <li>{@link #VANILLA}: exactly vanilla behavior; only optimizations with identical results.</li>
 * <li>{@link #BALANCED}: adds the mild activation range ({@link ActivationRange}).</li>
 * <li>{@link #PERFORMANCE}: further from vanilla, like Paper/Pufferfish defaults: a stronger
 * activation range, AI that thinks less often far from players ({@link BrainThrottle}) and at
 * most 8 entity pushes per tick ({@link Collisions}).</li>
 * </ul>
 * Each change also has its own system property, which overrides the edition's choice.
 */
public enum Edition {
	VANILLA,
	BALANCED,
	PERFORMANCE;

	public static final Edition CURRENT = parse(System.getProperty("mcrs.edition", "vanilla"));

	private static Edition parse(String name) {
		try {
			return valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			com.mojang.logging.LogUtils.getLogger().warn("[mcrs] unknown edition '{}', using vanilla", name);
			return VANILLA;
		}
	}

	/** {@code property} if set, otherwise whether the current edition is at least {@code from}. */
	public static boolean flag(String property, Edition from) {
		String value = System.getProperty(property);
		return value != null ? Boolean.parseBoolean(value) : CURRENT.ordinal() >= from.ordinal();
	}

	/** {@code property} if set, otherwise {@code performance} in the performance edition and {@code otherwise} below. */
	public static int value(String property, int otherwise, int performance) {
		return Integer.getInteger(property, CURRENT == PERFORMANCE ? performance : otherwise);
	}
}
