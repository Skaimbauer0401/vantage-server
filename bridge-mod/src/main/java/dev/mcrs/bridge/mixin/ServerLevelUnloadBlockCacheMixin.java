package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears the {@link FastBlocks} cache when a chunk unloads. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelUnloadBlockCacheMixin {
	@Inject(method = "unload", at = @At("HEAD"))
	private void mcrs$clear(LevelChunk chunk, CallbackInfo ci) {
		((FastBlocks.Cache) this).mcrs$clearBlockCache();
		((FastBlocks.BiomeCache) ((ServerLevel) (Object) this).getBiomeManager()).mcrs$clearBiomeCache();
	}
}
