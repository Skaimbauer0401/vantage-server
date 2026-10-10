package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.mcrs.bridge.ActivationRange;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The optional activation range: see {@link ActivationRange}. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelActivationMixin {
	@WrapWithCondition(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
	private boolean mcrs$tick(Entity entity) {
		return !ActivationRange.skipTick(entity);
	}
}
