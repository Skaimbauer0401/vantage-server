package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.ServerTuning;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Experience orbs merge from farther (performance edition, see {@link ServerTuning}). */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceMergeRadiusMixin {
	@WrapOperation(method = "scanForMerges", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"))
	private AABB mcrs$radius(AABB box, double amount, Operation<AABB> original) {
		return original.call(box, ServerTuning.MERGE_RADIUS ? 2.0 : amount);
	}
}
