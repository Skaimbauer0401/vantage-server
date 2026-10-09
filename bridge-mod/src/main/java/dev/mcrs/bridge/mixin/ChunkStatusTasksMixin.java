package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.ParallelFeatures;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Moves the FEATURES step off vanilla's serial worldgen executor; see {@link ParallelFeatures}. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
	@Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
	private static void mcrs$parallelFeatures(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
			ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (!ParallelFeatures.ENABLED || ParallelFeatures.BYPASS.get()) {
			return;
		}
		cir.setReturnValue(ParallelFeatures.run(context.level(), chunk.getPos(), net.minecraft.util.Util.backgroundExecutor(),
				() -> ChunkStatusTasks.generateFeatures(context, step, chunks, chunk).join()));
	}
}
