package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One bit per block of the section: whether its state or its fluid ticks randomly. Built on
 * first use and kept current at the same two places that keep vanilla's ticking counters
 * ({@code setBlockState}, {@code recalcBlockCounts}), so it is exactly as reliable as those.
 */
@Mixin(LevelChunkSection.class)
public abstract class SectionTickingBitsMixin implements FastBlocks.TickingBits {
	@Shadow
	@Final
	private PalettedContainer<BlockState> states;

	@Unique
	private long[] mcrs$ticking;

	@Unique
	private static boolean mcrs$ticks(BlockState state) {
		return state.isRandomlyTicking() || state.getFluidState().isRandomlyTicking();
	}

	@Override
	public long[] mcrs$tickingBits() {
		long[] bits = mcrs$ticking;
		if (bits == null) {
			bits = new long[64];
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (mcrs$ticks(states.get(x, y, z))) {
							int index = y << 8 | z << 4 | x;
							bits[index >>> 6] |= 1L << index;
						}
					}
				}
			}
			mcrs$ticking = bits;
		}
		return bits;
	}

	@Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
	private void mcrs$updateBit(int x, int y, int z, BlockState state, boolean checkThreading, CallbackInfoReturnable<BlockState> cir) {
		long[] bits = mcrs$ticking;
		if (bits != null) {
			int index = y << 8 | z << 4 | x;
			if (mcrs$ticks(state)) {
				bits[index >>> 6] |= 1L << index;
			} else {
				bits[index >>> 6] &= ~(1L << index);
			}
		}
	}

	@Inject(method = "recalcBlockCounts", at = @At("HEAD"))
	private void mcrs$dropBits(CallbackInfo ci) {
		mcrs$ticking = null;
	}
}
