package dev.mcrs.bridge.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorInvoker {
	@Invoker("getBiomeGenerationSettingsForCarver")
	BiomeGenerationSettings mcrs$getBiomeGenerationSettingsForCarver(BiomeResolver resolver, ChunkPos sourcePos);
}
