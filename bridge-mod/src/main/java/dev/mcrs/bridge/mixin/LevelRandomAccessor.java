package dev.mcrs.bridge.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface LevelRandomAccessor {
	@Accessor("random")
	RandomSource mcrs$random();

	@Accessor("randValue")
	void mcrs$setRandValue(int value);
}
