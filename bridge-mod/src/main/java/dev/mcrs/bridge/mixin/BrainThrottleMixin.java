package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.BrainThrottle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttled brains (performance edition, see {@link BrainThrottle}): on a skipped tick memories
 * still expire and running behaviors still tick; sensors and starting new behaviors wait.
 */
@Mixin(Brain.class)
public abstract class BrainThrottleMixin<E extends LivingEntity> {
	@Shadow
	private void forgetOutdatedMemories() {
	}

	@Shadow
	private void tickEachRunningBehavior(ServerLevel level, E body) {
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void mcrs$throttle(ServerLevel level, E body, CallbackInfo ci) {
		if (BrainThrottle.ENABLED && body instanceof Mob mob && BrainThrottle.skipBrainSelection(mob)) {
			forgetOutdatedMemories();
			tickEachRunningBehavior(level, body);
			ci.cancel();
		}
	}
}
