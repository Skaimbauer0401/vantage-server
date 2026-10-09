package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.TickHarness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds block changes to the tick harness's per-tick hash. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkHarnessMixin {
	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void mcrs$hashChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (TickHarness.DETERMINISTIC && cir.getReturnValue() != null) {
			TickHarness.onBlockChange(pos, state);
		}
	}
}
