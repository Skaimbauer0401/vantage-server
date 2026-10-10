package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastOres;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Whether the region's center chunk is upgrading (its write check then depends on Y). */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionAccessor implements FastOres.Region {
	@Shadow
	@Final
	private ChunkAccess center;

	@Override
	public boolean mcrs$centerUpgrading() {
		return center.isUpgrading();
	}
}
