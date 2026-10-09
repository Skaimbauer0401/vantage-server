package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.StageTimes;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stage timing for the benchmark; see {@link StageTimes}. */
@Mixin(OreFeature.class)
public abstract class OreTimingMixin {
	@Inject(method = "place", at = @At("HEAD"))
	private void mcrs$oreStart(CallbackInfoReturnable<Boolean> cir) {
		StageTimes.begin(StageTimes.ORES);
	}

	@Inject(method = "place", at = @At("RETURN"))
	private void mcrs$oreEnd(CallbackInfoReturnable<Boolean> cir) {
		StageTimes.end(StageTimes.ORES);
	}
}
