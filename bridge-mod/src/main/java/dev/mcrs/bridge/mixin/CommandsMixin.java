package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.Benchmarks;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers the /mcrs command. */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcrs$registerCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
		Benchmarks.register(((Commands) (Object) this).getDispatcher());
	}
}
