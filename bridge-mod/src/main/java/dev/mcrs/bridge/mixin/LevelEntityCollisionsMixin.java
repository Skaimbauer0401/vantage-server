package dev.mcrs.bridge.mixin;

import com.google.common.collect.ImmutableList;
import dev.mcrs.bridge.FastEntities;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * {@code EntityGetter.getEntityCollisions} for levels: vanilla's body, with the entity query
 * done by {@link FastEntities#collidingEntities} where it gives the same list.
 */
@Mixin(Level.class)
public abstract class LevelEntityCollisionsMixin {
	public List<VoxelShape> getEntityCollisions(final @Nullable Entity source, final AABB testArea) {
		if (testArea.getSize() < 1.0E-7) {
			return List.of();
		}

		AABB inflated = testArea.inflate(1.0E-7);
		Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
		List<Entity> collidingEntities = FastEntities.ENABLED && (Object) this instanceof ServerLevel level && FastEntities.usesVanillaCollideWith(source)
				? FastEntities.collidingEntities(level, source, inflated, canCollide)
				: ((Level) (Object) this).getEntities(source, inflated, canCollide);
		if (collidingEntities.isEmpty()) {
			return List.of();
		}

		ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(collidingEntities.size());

		for (Entity entity : collidingEntities) {
			shapes.add(Shapes.create(entity.getBoundingBox()));
		}

		return shapes.build();
	}
}
