package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps, next to the section's entities, the ones that may be collided with, in the same
 * relative order (both lists append on add and drop the entity on remove); see
 * {@link FastEntities}.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> implements FastEntities.Collidables {
	@Unique
	private final List<Object> mcrs$collidables = new ArrayList<>(0);

	@Override
	public List<Object> mcrs$collidables() {
		return mcrs$collidables;
	}

	@Inject(method = "add", at = @At("HEAD"))
	private void mcrs$trackAdd(T entity, CallbackInfo ci) {
		if (FastEntities.mayBeCollidedWith(entity)) {
			mcrs$collidables.add(entity);
		}
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void mcrs$trackRemove(T entity, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && FastEntities.mayBeCollidedWith(entity)) {
			mcrs$collidables.remove(entity);
		}
	}
}
