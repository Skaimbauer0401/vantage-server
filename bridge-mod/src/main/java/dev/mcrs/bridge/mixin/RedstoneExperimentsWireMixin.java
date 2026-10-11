package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.Redstone;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.flag.FeatureFlag;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Redstone experiments without the data pack (performance edition, see {@link Redstone}). */
@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneExperimentsWireMixin {
	@WrapOperation(method = "useExperimentalEvaluator", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/flag/FeatureFlagSet;contains(Lnet/minecraft/world/flag/FeatureFlag;)Z"))
	private static boolean mcrs$experiments(FeatureFlagSet features, FeatureFlag flag, Operation<Boolean> original) {
		return Redstone.EXPERIMENTS && flag == net.minecraft.world.flag.FeatureFlags.REDSTONE_EXPERIMENTS || original.call(features, flag);
	}
}
