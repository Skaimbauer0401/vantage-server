package dev.mcrs.bridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.raid.Raider;

/**
 * An optional activation range like Paper's, but milder: NOT vanilla behavior, so it is off
 * unless {@code -Dmcrs.activationRange=true}. A mob with no non-spectator player within its
 * range (a cube: monsters 48 blocks, water mobs and bats 24, others 32) runs its whole tick on
 * 1 of 4 ticks; on the others only vanilla's common tick (tick counter, invulnerability) and
 * its despawn timer advance. Mobs with a target, recently hurt, riding or ridden, leashed, in
 * love, in an active raid, and bosses always tick. Distant mobs then move, fall and think at a
 * quarter of the rate, which can slow farms that work far from players.
 */
public final class ActivationRange {
	public static final boolean ENABLED = Boolean.getBoolean("mcrs.activationRange");
	private static final int MONSTER_RANGE = Integer.getInteger("mcrs.activationRange.monsters", 48);
	private static final int WATER_RANGE = Integer.getInteger("mcrs.activationRange.water", 24);
	private static final int OTHER_RANGE = Integer.getInteger("mcrs.activationRange.others", 32);
	private static final int INTERVAL = 4;
	/** Whole mob ticks skipped (for benchmarks). */
	public static final java.util.concurrent.atomic.AtomicLong SKIPPED = new java.util.concurrent.atomic.AtomicLong();

	private ActivationRange() {
	}

	/** Whether the whole tick of the entity is skipped; keeps its despawn timer running if so. */
	public static boolean skipTick(Entity entity) {
		if (!ENABLED || !(entity instanceof Mob mob) || (mob.tickCount + mob.getId()) % INTERVAL == 0 || !(mob.level() instanceof ServerLevel level)) {
			return false;
		}
		if (mob.getTarget() != null
				|| mob.hurtTime > 0
				|| mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100
				|| mob.isPassenger()
				|| mob.isVehicle()
				|| mob.isLeashed()
				|| mob instanceof Animal animal && animal.isInLove()
				|| mob instanceof Raider raider && raider.hasActiveRaid()
				|| mob instanceof EnderDragon
				|| mob instanceof WitherBoss
				|| inRange(mob, level)) {
			return false;
		}
		dev.mcrs.bridge.mixin.LivingEntityJumpAccessor access = (dev.mcrs.bridge.mixin.LivingEntityJumpAccessor) mob;
		access.mcrs$setNoActionTime(access.mcrs$noActionTime() + 1);
		SKIPPED.incrementAndGet();
		return true;
	}

	private static boolean inRange(Mob mob, ServerLevel level) {
		int range = switch (mob.getType().getCategory()) {
			case MONSTER -> MONSTER_RANGE;
			case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS, AMBIENT -> WATER_RANGE;
			default -> OTHER_RANGE;
		};
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator() && Math.abs(player.getX() - mob.getX()) <= range && Math.abs(player.getY() - mob.getY()) <= range
					&& Math.abs(player.getZ() - mob.getZ()) <= range) {
				return true;
			}
		}
		return false;
	}
}
