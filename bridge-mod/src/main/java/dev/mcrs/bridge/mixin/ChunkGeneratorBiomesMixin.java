package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Biomes through Rust (see {@link Biomes}). */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorBiomesMixin {
	@Inject(method = "doCreateBiomes", at = @At("HEAD"), cancellable = true)
	private void mcrs$biomes(Blender blender, RandomState randomState, ChunkAccess chunk, CallbackInfo ci) {
		dev.mcrs.bridge.StageTimes.begin(dev.mcrs.bridge.StageTimes.BIOMES);
		if (Biomes.tryFill((ChunkGenerator) (Object) this, blender, randomState, chunk)) {
			// Cancelling skips the timing mixin's end, so end here.
			dev.mcrs.bridge.StageTimes.end(dev.mcrs.bridge.StageTimes.BIOMES);
			ci.cancel();
		}
	}
}
