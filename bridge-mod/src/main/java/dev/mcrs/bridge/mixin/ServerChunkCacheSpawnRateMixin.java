package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.SpawnRate;
import java.util.List;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Fewer natural spawn attempts (performance edition, see {@link SpawnRate}). */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheSpawnRateMixin {
	@Shadow
	@Final
	ServerLevel level;

	@WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/NaturalSpawner;getFilteredSpawningCategories(Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZ)Ljava/util/List;"))
	private List<MobCategory> mcrs$spawnRate(NaturalSpawner.SpawnState state, boolean spawnEnemies, boolean spawnPersistent, Operation<List<MobCategory>> original) {
		List<MobCategory> categories = original.call(state, spawnEnemies, spawnPersistent);
		return SpawnRate.ENABLED ? SpawnRate.filter(categories, level.getGameTime()) : categories;
	}
}
