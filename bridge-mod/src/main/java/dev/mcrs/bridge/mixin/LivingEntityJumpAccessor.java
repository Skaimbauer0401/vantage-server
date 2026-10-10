package dev.mcrs.bridge.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityJumpAccessor {
	@Accessor("noActionTime")
	int mcrs$noActionTime();

	@Accessor("noActionTime")
	void mcrs$setNoActionTime(int value);
}
