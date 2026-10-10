package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Block reads of collision iteration from a plain {@link LevelChunk} go straight to its
 * sections, with the same result as {@code LevelChunk.getBlockState} (outside the sections or
 * in an all-air section: air; never for the debug world, which the chunk special-cases).
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
	@Unique
	private BlockGetter mcrs$chunk;

	@Unique
	private LevelChunkSection[] mcrs$sections;

	@Unique
	private int mcrs$minSectionY;

	@Redirect(method = "computeNext", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState mcrs$read(BlockGetter chunk, BlockPos pos) {
		if (chunk != mcrs$chunk) {
			mcrs$chunk = chunk;
			mcrs$sections = null;
			if (FastBlocks.ENABLED && chunk.getClass() == LevelChunk.class && !((LevelChunk) chunk).getLevel().isDebug()) {
				mcrs$sections = ((LevelChunk) chunk).getSections();
				mcrs$minSectionY = ((LevelChunk) chunk).getMinSectionY();
			}
		}
		LevelChunkSection[] sections = mcrs$sections;
		if (sections == null) {
			return chunk.getBlockState(pos);
		}
		int y = pos.getY();
		int index = (y >> 4) - mcrs$minSectionY;
		if (index >= 0 && index < sections.length) {
			LevelChunkSection section = sections[index];
			if (!section.hasOnlyAir()) {
				return section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
			}
		}
		return Blocks.AIR.defaultBlockState();
	}
}
