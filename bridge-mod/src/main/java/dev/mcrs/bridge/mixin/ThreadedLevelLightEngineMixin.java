package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.Benchmarks;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures time in the (serial) light update, for the chunk benchmark. */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin {
	@Unique
	private long mcrs$start;

	@Inject(method = "runUpdate", at = @At("HEAD"))
	private void mcrs$start(CallbackInfo ci) {
		mcrs$start = System.nanoTime();
	}

	@Inject(method = "runUpdate", at = @At("RETURN"))
	private void mcrs$end(CallbackInfo ci) {
		Benchmarks.LIGHT_NANOS.addAndGet(System.nanoTime() - mcrs$start);
	}
}
