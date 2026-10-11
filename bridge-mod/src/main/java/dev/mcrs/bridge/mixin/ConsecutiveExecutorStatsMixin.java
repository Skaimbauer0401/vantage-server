package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.GenStats;
import net.minecraft.util.thread.AbstractConsecutiveExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Busy time per consecutive executor for {@code /mcrs genstats}. */
@Mixin(AbstractConsecutiveExecutor.class)
public abstract class ConsecutiveExecutorStatsMixin {
	@Shadow
	@Final
	private String name;

	@Unique
	private static final ThreadLocal<long[]> MCRS_START = ThreadLocal.withInitial(() -> new long[1]);

	@Inject(method = "pollTask", at = @At("HEAD"))
	private void mcrs$start(CallbackInfoReturnable<Boolean> cir) {
		MCRS_START.get()[0] = System.nanoTime();
	}

	@Inject(method = "pollTask", at = @At("RETURN"))
	private void mcrs$end(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			GenStats.lane(name, System.nanoTime() - MCRS_START.get()[0]);
		}
	}
}
