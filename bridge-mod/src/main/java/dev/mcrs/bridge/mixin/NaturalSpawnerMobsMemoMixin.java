package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside one {@code spawnCategoryForPosition} call, {@code mobsAt} for a position is asked
 * when choosing the mob and again when validating it; nothing it depends on (biome,
 * structures, the block below, environment attributes) changes within the call, so the last
 * answer is reused for the same level, category and position.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMobsMemoMixin {
	@Unique
	private static Thread mcrs$thread;
	@Unique
	private static ServerLevel mcrs$level;
	@Unique
	private static MobCategory mcrs$category;
	@Unique
	private static long mcrs$pos;
	@Unique
	private static WeightedList<MobSpawnSettings.SpawnerData> mcrs$mobs;

	@Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V", at = @At("HEAD"))
	private static void mcrs$begin(MobCategory category, ServerLevel level, ChunkAccess chunk, BlockPos start, NaturalSpawner.SpawnPredicate extraTest,
			NaturalSpawner.AfterSpawnCallback callback, CallbackInfo ci) {
		if (FastEntities.ENABLED) {
			mcrs$thread = Thread.currentThread();
			mcrs$mobs = null;
		}
	}

	@Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V", at = @At("RETURN"))
	private static void mcrs$end(MobCategory category, ServerLevel level, ChunkAccess chunk, BlockPos start, NaturalSpawner.SpawnPredicate extraTest,
			NaturalSpawner.AfterSpawnCallback callback, CallbackInfo ci) {
		mcrs$thread = null;
		mcrs$mobs = null;
		mcrs$level = null;
	}

	@Inject(method = "mobsAt", at = @At("HEAD"), cancellable = true)
	private static void mcrs$memo(ServerLevel level, StructureManager structureManager, ChunkGenerator generator, MobCategory category, BlockPos pos,
			CallbackInfoReturnable<WeightedList<MobSpawnSettings.SpawnerData>> cir) {
		if (mcrs$thread == Thread.currentThread() && mcrs$mobs != null && mcrs$level == level && mcrs$category == category && mcrs$pos == pos.asLong()) {
			cir.setReturnValue(mcrs$mobs);
		}
	}

	@Inject(method = "mobsAt", at = @At("RETURN"))
	private static void mcrs$remember(ServerLevel level, StructureManager structureManager, ChunkGenerator generator, MobCategory category, BlockPos pos,
			CallbackInfoReturnable<WeightedList<MobSpawnSettings.SpawnerData>> cir) {
		if (mcrs$thread == Thread.currentThread()) {
			mcrs$level = level;
			mcrs$category = category;
			mcrs$pos = pos.asLong();
			mcrs$mobs = cir.getReturnValue();
		}
	}
}
