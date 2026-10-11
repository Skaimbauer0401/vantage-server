package dev.mcrs.bridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/**
 * Mobs think less often the farther the nearest player is, like Pufferfish's "dynamic
 * activation of brain" but milder: NOT vanilla behavior, on only in the performance edition
 * ({@link Edition}) or with {@code -Dmcrs.brainThrottle=true}. Within 16 blocks of a player
 * nothing changes; beyond that a mob picks new goals and runs its brain's sensors and
 * behavior selection only every {@code distance² / 512} ticks (32 blocks: 2, 48: 4, 64: 8, at
 * most {@code -Dmcrs.brainThrottle.max}, default 8). Running goals and behaviors, navigation,
 * movement and physics still run every tick, so mobs keep moving smoothly; they only react
 * later. Mobs that {@link ActivationRange#alwaysActive} are never throttled.
 */
public final class BrainThrottle {
	public static final boolean ENABLED = Edition.flag("mcrs.brainThrottle", Edition.PERFORMANCE);
	private static final int MAX_INTERVAL = Math.max(1, Integer.getInteger("mcrs.brainThrottle.max", 8));
	private static final double FREE_DISTANCE_SQR = 16 * 16;
	/** Goal selections and brain selections skipped (for benchmarks). */
	public static final java.util.concurrent.atomic.AtomicLong SKIPPED = new java.util.concurrent.atomic.AtomicLong();

	private BrainThrottle() {
	}

	/** Ticks between full thinking for this mob (1 = every tick, as vanilla). */
	public static int interval(Mob mob) {
		if (!ENABLED || !(mob.level() instanceof ServerLevel level)) {
			return 1;
		}
		double nearest = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator()) {
				nearest = Math.min(nearest, player.distanceToSqr(mob));
			}
		}
		if (nearest <= FREE_DISTANCE_SQR || ActivationRange.alwaysActive(mob)) {
			return 1;
		}
		return (int) Math.max(1, Math.min(MAX_INTERVAL, nearest / 512));
	}

	/** Whether a goal selection that vanilla runs on this (even) tick is skipped. */
	public static boolean skipGoalSelection(Mob mob) {
		int interval = interval(mob);
		if (interval > 1 && ((mob.tickCount + mob.getId()) >> 1) % interval != 0) {
			SKIPPED.incrementAndGet();
			return true;
		}
		return false;
	}

	/** Whether the brain's sensors and behavior selection are skipped this tick. */
	public static boolean skipBrainSelection(Mob mob) {
		int interval = interval(mob);
		if (interval > 1 && (mob.tickCount + mob.getId()) % interval != 0) {
			SKIPPED.incrementAndGet();
			return true;
		}
		return false;
	}
}
