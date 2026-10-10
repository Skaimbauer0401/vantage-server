package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastOres;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Logs section writes while a verify mode records them. */
@Mixin(LevelChunkSection.class)
public abstract class SectionWriteLogMixin {
	@Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
	private void mcrs$log(int x, int y, int z, BlockState state, boolean checkThreading, CallbackInfoReturnable<BlockState> cir) {
		if (FastOres.VERIFY) {
			FastOres.recordWrite((LevelChunkSection) (Object) this, x, y, z, state);
		}
	}
}
