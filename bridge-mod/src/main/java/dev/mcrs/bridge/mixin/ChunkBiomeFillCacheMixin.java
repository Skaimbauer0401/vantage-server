package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears the biome cache when a level chunk's biomes are refilled (e.g. /fillbiome). */
@Mixin(ChunkAccess.class)
public abstract class ChunkBiomeFillCacheMixin {
	@Inject(method = "fillBiomesFromNoise", at = @At("RETURN"))
	private void mcrs$clear(BiomeResolver resolver, CallbackInfo ci) {
		if ((Object) this instanceof LevelChunk chunk) {
			((FastBlocks.BiomeCache) chunk.getLevel().getBiomeManager()).mcrs$clearBiomeCache();
		}
	}
}
