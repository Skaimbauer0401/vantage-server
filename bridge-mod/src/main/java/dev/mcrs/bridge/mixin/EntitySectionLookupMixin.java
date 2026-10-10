package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code forEachAccessibleNonEmptySection} by direct lookups: vanilla walks, for each section
 * X, every stored section key with that X (all Z and Y of the world) and keeps those in range.
 * For small boxes the keys in range are enumerated in the same order (by packed key: Z, then
 * Y, each as unsigned bits) and looked up in the section map, which holds exactly the keys of
 * the sorted set. Large boxes and extreme coordinates use vanilla's walk.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionLookupMixin<T extends EntityAccess> {
	@Unique
	private static final int MAX_CELLS = 64;

	@Shadow
	@Final
	private Long2ObjectMap<EntitySection<T>> sections;

	/** Values in [min, max] in the order of their low {@code bits} bits, unsigned. */
	@Unique
	private static int[] mcrs$unsignedOrder(int min, int max) {
		int[] out = new int[max - min + 1];
		int n = 0;
		for (int v = Math.max(min, 0); v <= max; v++) {
			out[n++] = v;
		}
		for (int v = min; v <= Math.min(max, -1); v++) {
			out[n++] = v;
		}
		return out;
	}

	@Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true)
	private void mcrs$direct(AABB bb, AbortableIterationConsumer<EntitySection<T>> output, CallbackInfo ci) {
		if (!FastEntities.ENABLED) {
			return;
		}
		int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
		int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
		int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
		int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
		int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
		int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
		long zCount = (long) zMax - zMin + 1;
		long yCount = (long) yMax - yMin + 1;
		if (zCount <= 0 || yCount <= 0 || zCount * yCount > MAX_CELLS || yMin < -(1 << 19) || yMax >= 1 << 19 || zMin < -(1 << 21) || zMax >= 1 << 21
				|| xMin < -(1 << 21) || xMax >= 1 << 21) {
			return;
		}
		ci.cancel();
		int[] zs = mcrs$unsignedOrder(zMin, zMax);
		int[] ys = mcrs$unsignedOrder(yMin, yMax);
		for (int x = xMin; x <= xMax; x++) {
			for (int z : zs) {
				for (int y : ys) {
					EntitySection<T> section = sections.get(SectionPos.asLong(x, y, z));
					if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && output.accept(section).shouldAbort()) {
						return;
					}
				}
			}
		}
	}
}
