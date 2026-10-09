package dev.mcrs.bridge.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SkyLightEngine.class)
public interface SkyLightEngineInvoker {
	@Invoker("countEmptySectionsBelowIfAtBorder")
	int mcrs$countEmptySectionsBelowIfAtBorder(long blockNode);

	@Invoker("propagateFromEmptySections")
	void mcrs$propagateFromEmptySections(long toNode, Direction direction, int toLevel, boolean increase, int emptySectionsBelow);
}
