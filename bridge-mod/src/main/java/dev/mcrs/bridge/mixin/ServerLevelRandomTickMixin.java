package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code ServerLevel.tickChunk} with the same random draws and calls, except that a picked
 * block whose section bit says it neither ticks nor holds a ticking fluid is skipped without
 * reading it (vanilla would read it and do nothing).
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRandomTickMixin {
	@Shadow
	protected abstract void tickPrecipitation(BlockPos pos);

	@Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
	private void mcrs$tickChunk(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		if (!FastBlocks.ENABLED) {
			return;
		}
		ci.cancel();
		ServerLevel level = (ServerLevel) (Object) this;
		net.minecraft.util.RandomSource random = ((LevelRandomAccessor) level).mcrs$random();
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		ProfilerFiller profiler = Profiler.get();
		profiler.push("iceandsnow");

		for (int i = 0; i < tickSpeed; i++) {
			if (random.nextInt(48) == 0) {
				this.tickPrecipitation(level.getBlockRandomPos(minX, 0, minZ, 15));
			}
		}

		profiler.popPush("tickBlocks");
		if (tickSpeed > 0) {
			LevelChunkSection[] sections = chunk.getSections();

			for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
				LevelChunkSection section = sections[sectionIndex];
				if (section.isRandomlyTicking()) {
					int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
					int minYInSection = SectionPos.sectionToBlockCoord(sectionY);
					long[] bits = ((FastBlocks.TickingBits) section).mcrs$tickingBits();

					for (int i = 0; i < tickSpeed; i++) {
						BlockPos pos = level.getBlockRandomPos(minX, minYInSection, minZ, 15);
						profiler.push("randomTick");
						int x = pos.getX() - minX;
						int y = pos.getY() - minYInSection;
						int z = pos.getZ() - minZ;
						int index = y << 8 | z << 4 | x;
						if ((bits[index >>> 6] & 1L << index) != 0) {
							BlockState blockState = section.getBlockState(x, y, z);
							if (blockState.isRandomlyTicking()) {
								blockState.randomTick(level, pos, random);
							}

							FluidState fluidState = blockState.getFluidState();
							if (fluidState.isRandomlyTicking()) {
								fluidState.randomTick(level, pos, random);
							}
						}

						profiler.pop();
					}
				}
			}
		}

		profiler.pop();
	}
}
