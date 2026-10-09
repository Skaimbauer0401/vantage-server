package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.StageTimes;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stage timing for the benchmark; see {@link StageTimes}. */
@Mixin(Heightmap.class)
public abstract class HeightmapTimingMixin {
	@Inject(method = "primeHeightmaps", at = @At("HEAD"))
	private static void mcrs$primeStart(CallbackInfo ci) {
		StageTimes.begin(StageTimes.HEIGHTMAPS);
	}

	@Inject(method = "primeHeightmaps", at = @At("RETURN"))
	private static void mcrs$primeEnd(CallbackInfo ci) {
		StageTimes.end(StageTimes.HEIGHTMAPS);
	}
}
