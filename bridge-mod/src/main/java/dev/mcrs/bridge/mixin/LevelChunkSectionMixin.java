package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.ChunkIo;
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

/**
 * Uses the block counts Rust computed while loading ({@code mcrs:counts}) instead of
 * scanning all 4096 blocks. Only applies to the exact container Rust's ids built.
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {
	@Shadow
	@Final
	private PalettedContainer<BlockState> states;
	@Shadow
	private short nonEmptyBlockCount;
	@Shadow
	private short fluidCount;
	@Shadow
	private short tickingBlockCount;
	@Shadow
	private short tickingFluidCount;

	@Unique
	private int[] mcrs$expectedCounts;

	@Inject(method = "recalcBlockCounts", at = @At("HEAD"), cancellable = true)
	private void mcrs$useRustCounts(CallbackInfo ci) {
		int[] counts = ChunkIo.takeCounts(states);
		if (counts == null) {
			return;
		}
		if (ChunkIo.VERIFY) {
			// Let vanilla count, then compare.
			mcrs$expectedCounts = counts;
			return;
		}
		nonEmptyBlockCount = (short) counts[0];
		fluidCount = (short) counts[1];
		tickingBlockCount = (short) counts[2];
		tickingFluidCount = (short) counts[3];
		ci.cancel();
	}

	@Inject(method = "recalcBlockCounts", at = @At("RETURN"))
	private void mcrs$verifyCounts(CallbackInfo ci) {
		int[] expected = mcrs$expectedCounts;
		if (expected != null) {
			mcrs$expectedCounts = null;
			ChunkIo.verifyCounts(new int[] {nonEmptyBlockCount, fluidCount, tickingBlockCount, tickingFluidCount}, expected);
		}
	}
}
