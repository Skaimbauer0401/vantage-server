package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.EntityGrid;
import dev.mcrs.bridge.FastEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps, next to the section's entities, the ones that may be collided with, in the same
 * relative order (both lists append on add and drop the entity on remove); see
 * {@link FastEntities}. Also keeps the section's {@link EntityGrid} and answers box queries
 * from it.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> implements FastEntities.Collidables {
	@Shadow
	@Final
	private ClassInstanceMultiMap<T> storage;

	@Unique
	private final List<Object> mcrs$collidables = new ArrayList<>(0);

	@Unique
	private final EntityGrid mcrs$grid = new EntityGrid();

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

	@Inject(method = "add", at = @At("RETURN"))
	private void mcrs$gridAdd(T entity, CallbackInfo ci) {
		if (EntityGrid.ENABLED) {
			mcrs$grid.add(entity);
		}
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void mcrs$trackRemove(T entity, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && FastEntities.mayBeCollidedWith(entity)) {
			mcrs$collidables.remove(entity);
		}
		if (EntityGrid.ENABLED && cir.getReturnValueZ()) {
			mcrs$grid.remove(entity);
		}
	}

	@Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/Continuation;", at = @At("HEAD"), cancellable = true)
	private void mcrs$gridQuery(AABB bb, AbortableIterationConsumer<T> entities, CallbackInfoReturnable<Continuation> cir) {
		if (EntityGrid.ENABLED) {
			Continuation result = mcrs$grid.forEach(bb, null, entities);
			if (result != null) {
				cir.setReturnValue(result);
			}
		}
	}

	@Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/Continuation;", at = @At("HEAD"), cancellable = true)
	private void mcrs$gridTypedQuery(EntityTypeTest<T, ?> type, AABB bb, AbortableIterationConsumer<?> consumer, CallbackInfoReturnable<Continuation> cir) {
		if (EntityGrid.ENABLED) {
			// Same lookup (and empty check) as vanilla first.
			Collection<?> found = storage.find(type.getBaseClass());
			if (found.isEmpty()) {
				cir.setReturnValue(Continuation.CONTINUE);
				return;
			}
			Continuation result = mcrs$grid.forEach(bb, type, consumer);
			if (result != null) {
				cir.setReturnValue(result);
			}
		}
	}
}
