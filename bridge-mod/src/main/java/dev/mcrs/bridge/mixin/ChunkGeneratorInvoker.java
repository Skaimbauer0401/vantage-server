package dev.mcrs.bridge.mixin;

import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorInvoker {
	@Invoker("decorateBiomeResolver")
	BiomeResolver mcrs$decorateBiomeResolver(Blender blender, ChunkAccess chunk, BiomeResolver resolver);
}
