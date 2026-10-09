package dev.mcrs.bridge.mixin;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LightEngine.class)
public interface LightEngineAccessor {
	@Accessor("storage")
	LayerLightSectionStorage<?> mcrs$storage();

	@Accessor("increaseQueue")
	LongArrayFIFOQueue mcrs$increaseQueue();

	@Invoker("enqueueIncrease")
	void mcrs$enqueueIncrease(long fromNode, long increaseData);
}
