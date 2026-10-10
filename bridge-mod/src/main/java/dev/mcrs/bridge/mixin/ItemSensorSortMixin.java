package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The nearest item sensor's distance sort, done by {@link FastEntities#sortByDistance}. */
@Mixin(NearestItemSensor.class)
public abstract class ItemSensorSortMixin {
	@Redirect(method = "doTick", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
	private void mcrs$sort(List<? extends Entity> list, Comparator<?> comparator, ServerLevel level, Mob body) {
		FastEntities.sortByDistance(list, body);
	}
}
