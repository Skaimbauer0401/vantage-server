package dev.mcrs.bridge;

import net.minecraft.world.flag.FeatureFlag;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;

/**
 * Mojang's experimental redstone (the "Redstone Experiments" data pack: its wire evaluator and
 * update orientations) without the data pack: NOT current vanilla behavior, on only in the
 * performance edition ({@link Edition}) or with {@code -Dmcrs.redstoneExperiments=true}. Worlds
 * keep no trace of it; turning it off again restores today's redstone. Contraptions that
 * depend on today's update order (some quasi-connectivity and wire-order tricks) can behave
 * differently.
 */
public final class Redstone {
	public static final boolean EXPERIMENTS = Edition.flag("mcrs.redstoneExperiments", Edition.PERFORMANCE);

	private Redstone() {
	}

	/** {@code features.contains(flag)}, with the redstone experiments forced on if enabled. */
	public static boolean contains(FeatureFlagSet features, FeatureFlag flag) {
		return EXPERIMENTS && flag == FeatureFlags.REDSTONE_EXPERIMENTS || features.contains(flag);
	}
}
