package dev.mcrs.bridge.mixin;

import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SkyLightSectionStorage.class)
public interface SkyLightSectionStorageInvoker {
	@Invoker("getTopSectionY")
	int mcrs$getTopSectionY(long zeroNode);

	@Invoker("getBottomSectionY")
	int mcrs$getBottomSectionY();
}
