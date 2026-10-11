package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.Collisions;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's pushEntities with at most {@link Collisions#MAX} pushes (performance edition). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCollisionLimitMixin {
	@Shadow
	protected abstract void doPush(Entity entity);

	@Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
	private void mcrs$limit(CallbackInfo ci) {
		if (!Collisions.ENABLED) {
			return;
		}
		ci.cancel();
		LivingEntity self = (LivingEntity) (Object) this;
		List<Entity> pushableEntities = self.level().getPushableEntities(self, self.getBoundingBox());
		if (pushableEntities.isEmpty()) {
			return;
		}
		if (self.level() instanceof ServerLevel serverLevel) {
			int maxCramming = serverLevel.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
			if (maxCramming > 0 && pushableEntities.size() > maxCramming - 1 && self.getRandom().nextInt(4) == 0) {
				int count = 0;
				for (Entity entity : pushableEntities) {
					if (!entity.isPassenger()) {
						count++;
					}
				}
				if (count > maxCramming - 1) {
					self.hurtServer(serverLevel, self.damageSources().cramming(), 6.0F);
				}
			}
		}
		int pushes = 0;
		for (Entity entity : pushableEntities) {
			if (pushes++ >= Collisions.MAX) {
				break;
			}
			doPush(entity);
		}
	}
}
