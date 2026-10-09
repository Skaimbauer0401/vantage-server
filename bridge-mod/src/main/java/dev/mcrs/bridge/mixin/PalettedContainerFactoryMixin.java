package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.ChunkIo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exports global palette ids to Rust once the server's registries exist. */
@Mixin(PalettedContainerFactory.class)
public abstract class PalettedContainerFactoryMixin {
	@Inject(method = "create", at = @At("RETURN"))
	private static void mcrs$exportRegistry(RegistryAccess registries, CallbackInfoReturnable<PalettedContainerFactory> cir) {
		ChunkIo.exportRegistry(cir.getReturnValue());
	}
}
