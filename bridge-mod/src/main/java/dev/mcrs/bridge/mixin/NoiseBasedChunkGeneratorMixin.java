package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.TerrainFill;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands terrain filling ({@code doFill}) to Rust where it can reproduce vanilla exactly. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@Shadow
	@Final
	private Holder<NoiseGeneratorSettings> settings;

	@Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
	private void mcrs$doFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
		if (!TerrainFill.VERIFY && TerrainFill.tryFill(noiseChunk, chunk, settings.value())) {
			ci.cancel();
		}
	}

	@Inject(method = "doFill", at = @At("RETURN"))
	private void mcrs$verifyFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
		if (TerrainFill.VERIFY) {
			TerrainFill.verify(noiseChunk, chunk, settings.value());
		}
	}
}
