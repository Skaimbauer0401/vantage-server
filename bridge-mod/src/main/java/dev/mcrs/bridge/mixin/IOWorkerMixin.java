package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.ChunkIo;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk reads go to Rust on a parallel pool instead of vanilla's single consecutive
 * executor. Only the pending-write check still runs on that executor, so a chunk
 * saved but not yet written is still served from memory exactly as in vanilla.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
	@Shadow
	@Final
	private SequencedMap<ChunkPos, ?> pendingWrites;

	@Shadow
	protected abstract <T> CompletableFuture<T> submitTask(Supplier<T> task);

	@Shadow
	public abstract CompletableFuture<Optional<CompoundTag>> loadAsync(ChunkPos pos);

	/** UTF-8 region folder for Rust, or null if this worker doesn't store chunks. */
	@Unique
	private MemorySegment mcrs$regionDir;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcrs$init(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
		if (ChunkIo.ENABLED && "chunk".equals(info.type())) {
			byte[] path = folder.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
			mcrs$regionDir = Arena.global().allocateFrom(ValueLayout.JAVA_BYTE, path);
		}
	}

	@Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
	private void mcrs$loadAsync(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
		if (mcrs$regionDir == null || ChunkIo.BYPASS.get() || !ChunkIo.active()) {
			return;
		}
		cir.setReturnValue(submitTask(() -> pendingWrites.containsKey(pos)).thenCompose(pending -> {
			if (pending) {
				return mcrs$vanillaLoad(pos);
			}
			return CompletableFuture.supplyAsync(() -> ChunkIo.read(mcrs$regionDir, pos), ChunkIo.EXECUTOR)
					.thenCompose(result -> result != null ? CompletableFuture.completedFuture(result) : mcrs$vanillaLoad(pos));
		}));
	}

	@Unique
	private CompletableFuture<Optional<CompoundTag>> mcrs$vanillaLoad(ChunkPos pos) {
		ChunkIo.BYPASS.set(true);
		try {
			return loadAsync(pos);
		} finally {
			ChunkIo.BYPASS.set(false);
		}
	}
}
