package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.ServerTuning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Breeding cap (performance edition, see {@link ServerTuning}). */
@Mixin(Animal.class)
public abstract class AnimalBreedingCapMixin {
	@Inject(method = "spawnChildFromBreeding", at = @At("HEAD"), cancellable = true)
	private void mcrs$cap(ServerLevel level, Animal partner, CallbackInfo ci) {
		Animal self = (Animal) (Object) this;
		if (ServerTuning.breedingCapped(self)) {
			self.setAge(6000);
			partner.setAge(6000);
			self.resetLove();
			partner.resetLove();
			ci.cancel();
		}
	}
}
