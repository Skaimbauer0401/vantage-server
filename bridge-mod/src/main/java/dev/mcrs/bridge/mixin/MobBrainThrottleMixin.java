package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.BrainThrottle;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Throttled goal selection (performance edition, see {@link BrainThrottle}): on a skipped tick
 * the running goals still tick as in a full selection, only stopping and starting goals waits.
 */
@Mixin(Mob.class)
public abstract class MobBrainThrottleMixin {
	@WrapOperation(method = "serverAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
	private void mcrs$throttle(GoalSelector selector, Operation<Void> original) {
		if (BrainThrottle.ENABLED && BrainThrottle.skipGoalSelection((Mob) (Object) this)) {
			selector.tickRunningGoals(true);
		} else {
			original.call(selector);
		}
	}
}
