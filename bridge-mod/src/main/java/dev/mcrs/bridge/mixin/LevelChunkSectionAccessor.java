package dev.mcrs.bridge.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {
	@Mutable
	@Accessor("states")
	void mcrs$setStates(PalettedContainer<BlockState> states);

	@Accessor("nonEmptyBlockCount")
	void mcrs$setNonEmptyBlockCount(short count);

	@Accessor("fluidCount")
	void mcrs$setFluidCount(short count);

	@Accessor("tickingBlockCount")
	void mcrs$setTickingBlockCount(short count);

	@Accessor("tickingFluidCount")
	void mcrs$setTickingFluidCount(short count);
}
