package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears the {@link FastBlocks} cache when a chunk's full status changes. */
@Mixin(ChunkMap.class)
public abstract class ChunkStatusBlockCacheMixin {
	@Shadow
	@Final
	ServerLevel level;

	@Inject(method = "onFullChunkStatusChange", at = @At("HEAD"))
	private void mcrs$clear(ChunkPos pos, FullChunkStatus status, CallbackInfo ci) {
		((FastBlocks.Cache) level).mcrs$clearBlockCache();
	}
}
