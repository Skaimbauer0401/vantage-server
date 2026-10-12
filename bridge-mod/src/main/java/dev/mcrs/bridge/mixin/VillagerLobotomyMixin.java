package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.ServerTuning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Trapped villagers think less often (performance edition, see {@link ServerTuning}). */
@Mixin(Villager.class)
public abstract class VillagerLobotomyMixin {
	@Unique
	private boolean mcrs$trapped;

	@WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
	private void mcrs$lobotomy(Brain<?> brain, ServerLevel level, net.minecraft.world.entity.LivingEntity body, Operation<Void> original) {
		if (ServerTuning.VILLAGER_LOBOTOMY) {
			Villager self = (Villager) (Object) this;
			int phase = (self.tickCount + self.getId()) % 100;
			if (phase == 0) {
				mcrs$trapped = ServerTuning.trapped(self);
			}
			if (mcrs$trapped && phase % ServerTuning.LOBOTOMY_INTERVAL != 0) {
				return;
			}
		}
		original.call(brain, level, body);
	}
}
