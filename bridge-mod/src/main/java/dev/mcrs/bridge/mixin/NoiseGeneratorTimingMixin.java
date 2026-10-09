package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.StageTimes;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stage timing for the benchmark; see {@link StageTimes}. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseGeneratorTimingMixin {
	@Inject(method = "createNoiseChunk", at = @At("HEAD"))
	private void mcrs$noiseChunkStart(CallbackInfoReturnable<NoiseChunk> cir) {
		StageTimes.begin(StageTimes.NOISE_CHUNK);
	}

	@Inject(method = "createNoiseChunk", at = @At("RETURN"))
	private void mcrs$noiseChunkEnd(CallbackInfoReturnable<NoiseChunk> cir) {
		StageTimes.end(StageTimes.NOISE_CHUNK);
	}

	@Inject(method = "generateCarvers", at = @At("HEAD"))
	private void mcrs$carversStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.CARVERS);
	}

	@Inject(method = "generateCarvers", at = @At("RETURN"))
	private void mcrs$carversEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.CARVERS);
	}

	@Inject(method = "spawnOriginalMobs", at = @At("HEAD"))
	private void mcrs$spawnStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.SPAWN);
	}

	@Inject(method = "spawnOriginalMobs", at = @At("RETURN"))
	private void mcrs$spawnEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.SPAWN);
	}
}
