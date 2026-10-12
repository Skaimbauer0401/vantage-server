package dev.mcrs.bridge;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Exact shortcuts for entity game logic; {@code -Dmcrs.fastEntities=false} turns them off.
 *
 * <p>Entity collision queries ({@code getEntityCollisions}, run for every movement) keep only
 * entities whose {@code canBeCollidedWith} is true; that is overridden by just a few classes
 * (boats, shulkers, happy ghasts). Each entity section keeps those entities in a side list in
 * the section's own order, so the query visits the same sections in the same order but only
 * the entities that could pass, and returns the same list. Moving entities that override
 * {@code canCollideWith} (boats, minecarts, wind charges) use vanilla's query.
 *
 * <p>Box queries on sections with many entities use the section's {@link EntityGrid}. POI
 * searches (villagers) skip chunk columns known to have no POI section.
 */
public final class FastEntities {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.fastEntities"));
	/** POI search and competitor scan optimizations ({@code -Dmcrs.fastPoi=false} turns them off). */
	public static final boolean FAST_POI = ENABLED && !"false".equals(System.getProperty("mcrs.fastPoi"));
	/** Compares every fast POI chunk query with vanilla's ({@code -Dmcrs.fastPoi.verify=true}). */
	public static final boolean FAST_POI_VERIFY = Boolean.getBoolean("mcrs.fastPoi.verify");
	public static final java.util.concurrent.atomic.AtomicLong POI_VERIFIED = new java.util.concurrent.atomic.AtomicLong();
	public static final java.util.concurrent.atomic.AtomicLong POI_MISMATCHED = new java.util.concurrent.atomic.AtomicLong();

	/** Whether entities of this class may answer {@code canBeCollidedWith} with true. */
	private static final ClassValue<Boolean> MAY_BE_COLLIDED_WITH = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("canBeCollidedWith", Entity.class).getDeclaringClass() != Entity.class;
			} catch (NoSuchMethodException e) {
				return true;
			}
		}
	};

	/** Whether this class uses {@code Entity.canCollideWith} unchanged. */
	private static final ClassValue<Boolean> VANILLA_COLLIDE_WITH = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("canCollideWith", Entity.class).getDeclaringClass() == Entity.class;
			} catch (NoSuchMethodException e) {
				return false;
			}
		}
	};

	private FastEntities() {
	}

	public static boolean mayBeCollidedWith(Object entity) {
		return entity instanceof Entity && MAY_BE_COLLIDED_WITH.get(entity.getClass());
	}

	/** Implemented by the entity section mixin. */
	public interface Collidables {
		java.util.List<Object> mcrs$collidables();
	}

	/** Implemented by the section storage mixin. */
	public interface Columns {
		/** Bit mask of the present sections of an unpacked chunk column, or -1 when unknown. */
		long mcrs$presentSections(long chunkKey);

		/** The stored section at a section key ({@code storage.get}); null if not stored. */
		java.util.Optional<?> mcrs$storedSection(long sectionKey);

		int mcrs$minSectionY();

		/** Vanilla's {@code getOrLoad}, for verification. */
		Optional<?> mcrs$getOrLoad(long sectionKey);
	}

	/** Whether {@link #collidingEntities} may answer for this moving entity. */
	public static boolean usesVanillaCollideWith(Entity source) {
		return source == null || VANILLA_COLLIDE_WITH.get(source.getClass());
	}

	/**
	 * {@code level.getEntities(source, bb, predicate)} for the collision predicates, visiting
	 * only each section's possibly collidable entities (the others fail the predicate).
	 */
	public static java.util.List<Entity> collidingEntities(ServerLevel level, Entity source, AABB bb, java.util.function.Predicate<? super Entity> selector) {
		net.minecraft.util.profiling.Profiler.get().incrementCounter("getEntities");
		java.util.List<Entity> output = com.google.common.collect.Lists.newArrayList();
		((dev.mcrs.bridge.mixin.EntityManagerAccessor) ((dev.mcrs.bridge.mixin.ServerLevelEntityAccessor) level).mcrs$entityManager()).mcrs$sectionStorage()
				.forEachAccessibleNonEmptySection(bb, section -> {
					for (Object o : ((Collidables) section).mcrs$collidables()) {
						Entity entity = (Entity) o;
						if (entity.getBoundingBox().intersects(bb) && entity != source && selector.test(entity)) {
							output.add(entity);
						}
					}
					return net.minecraft.util.Continuation.CONTINUE;
				});
		for (net.minecraft.world.entity.boss.enderdragon.EnderDragonPart dragonPart : level.dragonParts()) {
			if (dragonPart != source && dragonPart.parentMob != source && selector.test(dragonPart) && bb.intersects(dragonPart.getBoundingBox())) {
				output.add(dragonPart);
			}
		}
		return output;
	}

	/**
	 * {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))}: computes each distance
	 * once and sorts by (distance per {@link Double#compare}, original position), which is the
	 * one order a stable sort by distance gives.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void sortByDistance(java.util.List<? extends Entity> list, Entity body) {
		int n = list.size();
		if (!ENABLED || n < 2 || !(list instanceof java.util.ArrayList)) {
			list.sort(java.util.Comparator.comparingDouble(body::distanceToSqr));
			return;
		}
		Object[] entities = list.toArray();
		double[] keys = new double[n];
		for (int i = 0; i < n; i++) {
			keys[i] = body.distanceToSqr((Entity) entities[i]);
		}
		int[] order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		mergeSort(order, new int[n], 0, n, keys);
		java.util.List raw = list;
		for (int i = 0; i < n; i++) {
			raw.set(i, entities[order[i]]);
		}
	}

	private static void mergeSort(int[] a, int[] tmp, int from, int to, double[] keys) {
		if (to - from <= 16) {
			for (int i = from + 1; i < to; i++) {
				int v = a[i];
				int j = i - 1;
				while (j >= from && Double.compare(keys[a[j]], keys[v]) > 0) {
					a[j + 1] = a[j];
					j--;
				}
				a[j + 1] = v;
			}
			return;
		}
		int mid = (from + to) >>> 1;
		mergeSort(a, tmp, from, mid, keys);
		mergeSort(a, tmp, mid, to, keys);
		System.arraycopy(a, from, tmp, from, to - from);
		int i = from, j = mid, k = from;
		while (i < mid && j < to) {
			a[k++] = Double.compare(keys[tmp[j]], keys[tmp[i]]) < 0 ? tmp[j++] : tmp[i++];
		}
		while (i < mid) {
			a[k++] = tmp[i++];
		}
		while (j < to) {
			a[k++] = tmp[j++];
		}
	}
}
