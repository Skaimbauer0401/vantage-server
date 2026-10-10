package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The nearest living entity sensor's distance sort, done by {@link FastEntities#sortByDistance}. */
@Mixin(NearestLivingEntitySensor.class)
public abstract class SensorSortMixin {
	@Redirect(method = "doTick", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
	private void mcrs$sort(List<? extends Entity> list, Comparator<?> comparator, ServerLevel level, LivingEntity body) {
		FastEntities.sortByDistance(list, body);
	}
}
