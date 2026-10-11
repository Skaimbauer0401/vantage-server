package dev.mcrs.bridge;

import java.util.List;
import net.minecraft.world.entity.MobCategory;

/**
 * Natural spawn attempts less often, like Bukkit's {@code ticks-per} spawn settings: NOT vanilla
 * behavior, on only in the performance edition ({@link Edition}) or with
 * {@code -Dmcrs.spawnRate=true}. Monsters try every {@code -Dmcrs.spawnRate.monsters} ticks
 * (default 2), water mobs, axolotls and bats every {@code -Dmcrs.spawnRate.others} (default 4);
 * animals keep vanilla's every 400 ticks. Mob caps stay the same, so areas fill up to the same
 * number of mobs, only more slowly.
 */
public final class SpawnRate {
	public static final boolean ENABLED = Edition.flag("mcrs.spawnRate", Edition.PERFORMANCE);
	private static final int MONSTERS = Math.max(1, Integer.getInteger("mcrs.spawnRate.monsters", 2));
	private static final int OTHERS = Math.max(1, Integer.getInteger("mcrs.spawnRate.others", 4));

	private SpawnRate() {
	}

	/** Removes the categories that don't try to spawn on this tick. */
	public static List<MobCategory> filter(List<MobCategory> categories, long gameTime) {
		categories.removeIf(category -> {
			int every = switch (category) {
				case MONSTER -> MONSTERS;
				case CREATURE, MISC -> 1;
				default -> OTHERS;
			};
			return gameTime % every != 0;
		});
		return categories;
	}
}
