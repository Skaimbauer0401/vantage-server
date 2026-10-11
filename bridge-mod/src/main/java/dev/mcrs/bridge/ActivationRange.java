package dev.mcrs.bridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.raid.Raider;

/**
 * An activation range like Paper's: NOT vanilla behavior, so it is on only in the balanced and
 * performance editions ({@link Edition}) or with {@code -Dmcrs.activationRange=true}. A mob
 * with no non-spectator player within its range (a cube) runs its whole tick on 1 of
 * {@code interval} ticks; on the others only vanilla's common tick (tick counter,
 * invulnerability) and its despawn timer advance. Mobs with a target, recently hurt, riding or
 * ridden, leashed, in love, in an active raid, and bosses always tick ({@link #alwaysActive}).
 * Distant mobs then move, fall and think less often, which can slow farms far from players.
 * <ul>
 * <li>balanced: monsters 48 blocks, water mobs and bats 24, others 32; every 4th tick.</li>
 * <li>performance: monsters 32, water mobs and bats 16, others 24; every 8th tick. Also item
 * entities lying still on the ground with no player within 32 blocks: their age and pickup delay
 * still count every tick (so they despawn on time), but merging and physics run only on every
 * 8th tick. Moving items (falling, in water streams) always tick.</li>
 * </ul>
 */
public final class ActivationRange {
	public static final boolean ENABLED = Edition.flag("mcrs.activationRange", Edition.BALANCED);
	private static final int MONSTER_RANGE = Edition.value("mcrs.activationRange.monsters", 48, 32);
	private static final int WATER_RANGE = Edition.value("mcrs.activationRange.water", 24, 16);
	private static final int OTHER_RANGE = Edition.value("mcrs.activationRange.others", 32, 24);
	public static final boolean ITEMS = ENABLED && Edition.flag("mcrs.activationRange.items", Edition.PERFORMANCE);
	private static final int ITEM_RANGE = Integer.getInteger("mcrs.activationRange.itemRange", 32);
	private static final int INTERVAL = Math.max(1, Edition.value("mcrs.activationRange.interval", 4, 8));
	/** Whole mob ticks skipped (for benchmarks). */
	public static final java.util.concurrent.atomic.AtomicLong SKIPPED = new java.util.concurrent.atomic.AtomicLong();

	private ActivationRange() {
	}

	/** Whether the whole tick of the entity is skipped; keeps its despawn timer running if so. */
	public static boolean skipTick(Entity entity) {
		if (ITEMS && entity instanceof ItemEntity item) {
			return skipItemTick(item);
		}
		if (!ENABLED || !(entity instanceof Mob mob) || (mob.tickCount + mob.getId()) % INTERVAL == 0 || !(mob.level() instanceof ServerLevel level)) {
			return false;
		}
		if (alwaysActive(mob) || inRange(mob, level)) {
			return false;
		}
		dev.mcrs.bridge.mixin.LivingEntityJumpAccessor access = (dev.mcrs.bridge.mixin.LivingEntityJumpAccessor) mob;
		access.mcrs$setNoActionTime(access.mcrs$noActionTime() + 1);
		SKIPPED.incrementAndGet();
		return true;
	}

	private static boolean skipItemTick(ItemEntity item) {
		if ((item.tickCount + item.getId()) % INTERVAL == 0 || !item.onGround() || item.getDeltaMovement().horizontalDistanceSqr() > 1.0E-5
				|| item.getItem().isEmpty() || !(item.level() instanceof ServerLevel level) || inRange(item, level, ITEM_RANGE)) {
			return false;
		}
		dev.mcrs.bridge.mixin.ItemEntityAccessor access = (dev.mcrs.bridge.mixin.ItemEntityAccessor) item;
		int pickupDelay = access.mcrs$pickupDelay();
		if (pickupDelay > 0 && pickupDelay != 32767) {
			access.mcrs$setPickupDelay(pickupDelay - 1);
		}
		int age = access.mcrs$age();
		if (age != -32768) {
			access.mcrs$setAge(++age);
		}
		if (age >= 6000) {
			item.discard();
		}
		SKIPPED.incrementAndGet();
		return true;
	}

	/** Mobs that are never slowed down: fighting, hurt, riding or ridden, leashed, breeding, raiding, bosses. */
	public static boolean alwaysActive(Mob mob) {
		return mob.getTarget() != null
				|| mob.hurtTime > 0
				|| mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100
				|| mob.isPassenger()
				|| mob.isVehicle()
				|| mob.isLeashed()
				|| mob instanceof Animal animal && animal.isInLove()
				|| mob instanceof Raider raider && raider.hasActiveRaid()
				|| mob instanceof EnderDragon
				|| mob instanceof WitherBoss;
	}

	private static boolean inRange(Mob mob, ServerLevel level) {
		int range = switch (mob.getType().getCategory()) {
			case MONSTER -> MONSTER_RANGE;
			case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS, AMBIENT -> WATER_RANGE;
			default -> OTHER_RANGE;
		};
		return inRange(mob, level, range);
	}

	private static boolean inRange(Entity mob, ServerLevel level, int range) {
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator() && Math.abs(player.getX() - mob.getX()) <= range && Math.abs(player.getY() - mob.getY()) <= range
					&& Math.abs(player.getZ() - mob.getZ()) <= range) {
				return true;
			}
		}
		return false;
	}
}
