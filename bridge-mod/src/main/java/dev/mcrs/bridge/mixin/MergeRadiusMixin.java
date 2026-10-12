package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.ServerTuning;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Items merge from farther (performance edition, see {@link ServerTuning}). */
@Mixin(ItemEntity.class)
public abstract class MergeRadiusMixin {
	@WrapOperation(method = "mergeWithNeighbours", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(DDD)Lnet/minecraft/world/phys/AABB;"))
	private AABB mcrs$radius(AABB box, double x, double y, double z, Operation<AABB> original) {
		return ServerTuning.MERGE_RADIUS ? original.call(box, 1.5, 0.5, 1.5) : original.call(box, x, y, z);
	}
}
