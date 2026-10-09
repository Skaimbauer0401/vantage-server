package dev.mcrs.bridge.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerRandomAccessor {
	@Accessor("random")
	RandomSource mcrs$random();
}
