package dev.mcrs.bridge.mixin;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerAccessor {
	@Accessor("sectionStorage")
	EntitySectionStorage<?> mcrs$sectionStorage();
}
