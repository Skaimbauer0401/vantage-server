package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.TerrainFill;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands terrain filling ({@code doFill}) and the surface step ({@code buildSurface})
 * to Rust where it reproduces vanilla exactly; see {@link TerrainFill}.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@Shadow
	@Final
	private Holder<NoiseGeneratorSettings> settings;

	/** doFill runs asynchronously; remember the chunk's BiomeManager for it. */
	@Inject(method = "buildTerrain", at = @At("HEAD"))
	private void mcrs$captureBiomes(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
			BiomeManager biomeManager, WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		TerrainFill.BIOME_MANAGERS.put(chunk, biomeManager);
	}

	@Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
	private void mcrs$doFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
		dev.mcrs.bridge.StageTimes.begin(dev.mcrs.bridge.StageTimes.FILL);
		boolean filled = !TerrainFill.VERIFY && TerrainFill.tryFill(noiseChunk, chunk, settings.value(), (ChunkGenerator) (Object) this);
		dev.mcrs.bridge.StageTimes.end(dev.mcrs.bridge.StageTimes.FILL);
		if (filled) {
			ci.cancel();
		}
	}

	@Inject(method = "doFill", at = @At("RETURN"))
	private void mcrs$verifyFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
		if (TerrainFill.VERIFY && !TerrainFill.surfaceInRust(noiseChunk, chunk, settings.value(), (ChunkGenerator) (Object) this)) {
			TerrainFill.verify(noiseChunk, chunk, settings.value(), (ChunkGenerator) (Object) this);
		}
	}

	@Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
	private void mcrs$skipSurface(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState, BiomeManager biomeManager,
			Set<Holder<Biome>> possibleBiomes, MaterialRule materialRule, CallbackInfo ci) {
		if (!TerrainFill.VERIFY) {
			TerrainFill.BIOME_MANAGERS.remove(chunk);
		}
		if (TerrainFill.SURFACE_DONE.remove(chunk)) {
			ci.cancel();
		}
	}

	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void mcrs$verifySurface(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState, BiomeManager biomeManager,
			Set<Holder<Biome>> possibleBiomes, MaterialRule materialRule, CallbackInfo ci) {
		if (TerrainFill.VERIFY) {
			if (TerrainFill.surfaceInRust(noiseChunk)) {
				TerrainFill.verify(noiseChunk, chunk, settings.value(), (ChunkGenerator) (Object) this);
			}
			TerrainFill.BIOME_MANAGERS.remove(chunk);
		}
	}
}
