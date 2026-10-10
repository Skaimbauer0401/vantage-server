package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastOres;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ore placement by {@link FastOres} (exact class only; subclasses keep their own code). In
 * verify mode the fast run happens and is undone first, then vanilla runs and both are compared.
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
	@Inject(method = "doPlace", at = @At("HEAD"), cancellable = true)
	private void mcrs$fastPlace(WorldGenLevel level, RandomSource random, double x0, double x1, double z0, double z1, double y0, double y1, int xStart, int yStart,
			int zStart, int sizeXZ, int sizeY, CallbackInfoReturnable<Boolean> cir) {
		if (FastOres.ENABLED && ((Object) this).getClass() == OreFeature.class) {
			if (FastOres.VERIFY) {
				FastOres.verifyBefore((OreFeature) (Object) this, level, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY);
			} else {
				cir.setReturnValue(FastOres.doPlace((OreFeature) (Object) this, level, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY));
			}
		}
	}

	@Inject(method = "doPlace", at = @At("RETURN"))
	private void mcrs$verify(WorldGenLevel level, RandomSource random, double x0, double x1, double z0, double z1, double y0, double y1, int xStart, int yStart,
			int zStart, int sizeXZ, int sizeY, CallbackInfoReturnable<Boolean> cir) {
		if (FastOres.VERIFY && FastOres.ENABLED && ((Object) this).getClass() == OreFeature.class) {
			FastOres.verifyAfter(random, cir.getReturnValueZ());
		}
	}
}
