package dev.mcrs.bridge.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
	@Accessor("changedSections")
	LongSet mcrs$changedSections();

	@Accessor("sectionsAffectedByLightUpdates")
	LongSet mcrs$sectionsAffected();

	@Accessor("updatingSectionData")
	DataLayerStorageMap<?> mcrs$updating();
}
