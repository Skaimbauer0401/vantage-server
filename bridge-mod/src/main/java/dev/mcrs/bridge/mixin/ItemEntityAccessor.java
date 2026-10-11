package dev.mcrs.bridge.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
	@Accessor("age")
	int mcrs$age();

	@Accessor("age")
	void mcrs$setAge(int value);

	@Accessor("pickupDelay")
	int mcrs$pickupDelay();

	@Accessor("pickupDelay")
	void mcrs$setPickupDelay(int value);
}
