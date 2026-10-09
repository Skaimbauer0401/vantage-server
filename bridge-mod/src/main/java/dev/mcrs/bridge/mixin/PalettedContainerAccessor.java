package dev.mcrs.bridge.mixin;

import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PalettedContainer.class)
public interface PalettedContainerAccessor<T> {
	@Accessor("strategy")
	Strategy<T> mcrs$strategy();
}
