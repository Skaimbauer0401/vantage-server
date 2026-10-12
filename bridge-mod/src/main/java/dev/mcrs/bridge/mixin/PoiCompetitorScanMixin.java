package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.List;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.PoiCompetitorScan;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The competitor scan of villagers with a job site, as a loop instead of a stream pipeline:
 * the same checks in the same order per nearby entity, and the same sequential reduction
 * (whose losers forget their job site), without the per-call stream objects. In trading halls
 * this ran for every villager every tick over all its neighbors.
 */
@Mixin(PoiCompetitorScan.class)
public abstract class PoiCompetitorScanMixin {
	@Shadow
	private static Villager selectWinner(Villager first, Villager second) {
		throw new AssertionError();
	}

	@Shadow
	private static boolean competesForSameJobsite(GlobalPos pos, Holder<PoiType> poiType, Villager nearbyVillager) {
		throw new AssertionError();
	}

	@Inject(method = "lambda$create$3", at = @At("HEAD"), cancellable = true)
	private static void mcrs$scan(BehaviorBuilder.Instance<?> i, MemoryAccessor<?, ?> nearestEntities, Villager body, GlobalPos pos, Holder<PoiType> poiType,
			CallbackInfo ci) {
		if (!FastEntities.FAST_POI) {
			return;
		}
		@SuppressWarnings({"unchecked", "rawtypes"})
		List<LivingEntity> entities = (List<LivingEntity>) ((BehaviorBuilder.Instance) i).get((MemoryAccessor) nearestEntities);
		Villager winner = body;
		for (LivingEntity entity : entities) {
			if (entity instanceof Villager villager && entity != body && villager.isAlive() && competesForSameJobsite(pos, poiType, villager)) {
				winner = selectWinner(winner, villager);
			}
		}
		ci.cancel();
	}
}
