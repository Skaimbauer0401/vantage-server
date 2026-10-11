package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.ParallelSave;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Encodes saved chunks right after building their NBT (see {@link ParallelSave}). */
@Mixin(ChunkMap.class)
public abstract class ChunkMapParallelSaveMixin {
	@WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private CompletableFuture<CompoundTag> mcrs$encode(Supplier<CompoundTag> write, Executor executor, Operation<CompletableFuture<CompoundTag>> original) {
		if (!ParallelSave.ENABLED) {
			return original.call(write, executor);
		}
		Supplier<CompoundTag> encoding = () -> {
			CompoundTag tag = write.get();
			ParallelSave.encode(tag);
			return tag;
		};
		return original.call(encoding, executor);
	}
}
