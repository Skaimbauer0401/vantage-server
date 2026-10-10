package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drops a position from the {@link FastBlocks} cache around every block change. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkBlockCacheMixin {
	@Shadow
	@Final
	Level level;

	@Inject(method = "setBlockState", at = @At("HEAD"))
	private void mcrs$invalidateBefore(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		((FastBlocks.Cache) level).mcrs$invalidate(pos.asLong());
	}

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void mcrs$invalidateAfter(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		((FastBlocks.Cache) level).mcrs$invalidate(pos.asLong());
	}
}
