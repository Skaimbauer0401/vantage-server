package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.mcrs.bridge.Benchmarks;
import dev.mcrs.bridge.NativeBridge;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures every server tick for Rust telemetry (MSPT/TPS) and benchmarks. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Unique
	private long mcrs$tickStart;

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void mcrs$tickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		mcrs$tickStart = System.nanoTime();
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void mcrs$tickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		long nanos = System.nanoTime() - mcrs$tickStart;
		if (NativeBridge.isAvailable()) {
			NativeBridge.onTick(nanos);
		}
		Benchmarks.onTick(nanos);
	}

	/**
	 * Benchmarks need real ticks; vanilla pauses an empty server after 60 s. Hooked at the
	 * call site because DedicatedServer overrides pauseWhenEmptySeconds().
	 */
	@ModifyExpressionValue(method = "tickServer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/MinecraftServer;pauseWhenEmptySeconds()I"))
	private int mcrs$noPauseDuringBenchmark(int seconds) {
		return Benchmarks.isRunning() ? 0 : seconds;
	}
}
