package dev.mcrs.bridge.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
	@Accessor("changedSections")
	LongSet mcrs$changedSections();

	@Accessor("sectionsAffectedByLightUpdates")
	LongSet mcrs$sectionsAffected();

	@Accessor("updatingSectionData")
	DataLayerStorageMap<?> mcrs$updating();

	@Invoker("setLightEnabled")
	void mcrs$setLightEnabled(long zeroNode, boolean enable);

	@Invoker("getDataLayerToWrite")
	DataLayer mcrs$getDataLayerToWrite(long sectionNode);
}
