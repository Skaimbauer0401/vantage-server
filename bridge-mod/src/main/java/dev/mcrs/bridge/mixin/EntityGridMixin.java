package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.EntityGrid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the entity's {@link EntityGrid} cell current; {@code setBoundingBox} is the only writer of its box. */
@Mixin(Entity.class)
public abstract class EntityGridMixin implements EntityGrid.Holder {
	@Unique
	private EntityGrid.Node mcrs$gridNode;

	@Override
	public EntityGrid.Node mcrs$gridNode() {
		EntityGrid.Node node = mcrs$gridNode;
		if (node == null) {
			node = mcrs$gridNode = new EntityGrid.Node((Entity) (Object) this);
		}
		return node;
	}

	@Inject(method = "setBoundingBox", at = @At("RETURN"))
	private void mcrs$moved(AABB bb, CallbackInfo ci) {
		EntityGrid.Node node = mcrs$gridNode;
		if (node != null) {
			EntityGrid.moved(node, bb);
		}
	}
}
