package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.StageTimes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stage timing for the benchmark; see {@link StageTimes}. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorTimingMixin {
	@Inject(method = "doCreateBiomes", at = @At("HEAD"))
	private void mcrs$biomesStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.BIOMES);
	}

	@Inject(method = "doCreateBiomes", at = @At("RETURN"))
	private void mcrs$biomesEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.BIOMES);
	}

	@Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
	private void mcrs$featuresStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.FEATURES);
	}

	@Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
	private void mcrs$featuresEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.FEATURES);
	}

	@Inject(method = "createStructures", at = @At("HEAD"))
	private void mcrs$startsStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.STARTS);
	}

	@Inject(method = "createStructures", at = @At("RETURN"))
	private void mcrs$startsEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.STARTS);
	}

	@Inject(method = "createReferences", at = @At("HEAD"))
	private void mcrs$refsStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.REFS);
	}

	@Inject(method = "createReferences", at = @At("RETURN"))
	private void mcrs$refsEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.REFS);
	}
}
