package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.ParallelSave;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Writes chunks encoded in advance as they are (see {@link ParallelSave}). */
@Mixin(IOWorker.class)
public abstract class IOWorkerParallelSaveMixin {
	@WrapOperation(method = "runStore", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"))
	private void mcrs$writeEncoded(RegionFileStorage storage, ChunkPos pos, CompoundTag value, Operation<Void> original) throws IOException {
		if (ParallelSave.ENABLED && value != null) {
			RegionFile region = ((RegionFileStorageInvoker) (Object) storage).mcrs$getOrCreateRegionFile(pos);
			ParallelSave.Encoded encoded = ParallelSave.take(value, ((RegionFileInvoker) region).mcrs$version());
			if (encoded != null) {
				((RegionFileInvoker) region).mcrs$write(pos, ParallelSave.buffer(encoded));
				return;
			}
		}
		original.call(storage, pos, value);
	}
}
