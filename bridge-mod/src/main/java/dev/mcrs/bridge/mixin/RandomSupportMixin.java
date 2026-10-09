package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.TickHarness;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reproducible "unique" seeds for the tick harness ({@code -Dmcrs.deterministic}). */
@Mixin(RandomSupport.class)
public abstract class RandomSupportMixin {
	@Inject(method = "generateUniqueSeed", at = @At("HEAD"), cancellable = true)
	private static void mcrs$deterministicSeed(CallbackInfoReturnable<Long> cir) {
		if (TickHarness.DETERMINISTIC) {
			cir.setReturnValue(TickHarness.uniqueSeed());
		}
	}
}
