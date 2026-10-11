package dev.mcrs.bridge;

/**
 * Idle hoppers wait before trying again: NOT vanilla behavior, on only in the performance
 * edition ({@link Edition}) or with {@code -Dmcrs.hopperIdle=true}. An enabled hopper that could
 * neither push nor pull anything waits {@code -Dmcrs.hopperIdle.ticks} ticks (default 4) instead
 * of trying every tick. Items moving through hopper chains are unaffected (a hopper receiving
 * an item from another hopper gets vanilla's 8-tick cooldown either way, and a filter hopper
 * still checks the line above it at least once while an item passes); idle hoppers react up to
 * 4 ticks later to new items. Locked hoppers (powered) stay vanilla, so hopper clocks keep
 * their timing.
 */
public final class Hoppers {
	public static final boolean ENABLED = Edition.flag("mcrs.hopperIdle", Edition.PERFORMANCE);
	public static final int IDLE_TICKS = Math.max(1, Math.min(7, Integer.getInteger("mcrs.hopperIdle.ticks", 4)));

	private Hoppers() {
	}
}
