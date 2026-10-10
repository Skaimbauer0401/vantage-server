package dev.mcrs.bridge;

import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * Exact spatial index of one entity section; {@code -Dmcrs.entityGrid=false} turns it off.
 *
 * <p>Vanilla answers a box query on a section by testing every entity of the section in the
 * section's order. The grid files each entity under the 2×2 block column cell of its box's
 * minimum corner (cells wrap modulo 16). An entity whose box intersects the query box has its
 * minimum corner in {@code (query.min - widest box in the section, query.max)}, so the cells
 * covering that range hold every hit. Their entities get vanilla's exact test, and the hits go
 * to the consumer sorted by when they joined the section, which is the section's list order
 * (vanilla appends on add and removes in place). Queries spanning many cells, small sections,
 * huge coordinates and anything unusual fall back to vanilla's scan.
 */
public final class EntityGrid {
	public static final boolean ENABLED = FastEntities.ENABLED && !"false".equals(System.getProperty("mcrs.entityGrid"));
	private static final int SHIFT = 1;
	private static final int MOD = 16;
	private static final int MASK = MOD - 1;
	private static final double MAX_SPAN = 12.0;
	private static final double MARGIN = 0.01;
	private static final double MAX_COORD = 1.0e8;
	private static final int MIN_SIZE = 8;
	private static final Comparator<Node> BY_SEQ = Comparator.comparingLong(n -> n.seq);
	private static long nextSeq;

	/** Index state of one entity, kept on the entity. */
	public static final class Node {
		final Entity entity;
		EntityGrid grid;
		int bucket;
		int slot;
		long seq;

		public Node(Entity entity) {
			this.entity = entity;
		}
	}

	/** Implemented by the entity mixin. */
	public interface Holder {
		Node mcrs$gridNode();
	}

	private Node[][] buckets;
	private int[] sizes;
	private int count;
	private double maxWidth;
	private double maxDepth;
	private boolean broken;

	private static int bucketOf(AABB bb) {
		return ((Mth.floor(bb.minX) >> SHIFT) & MASK) * MOD + ((Mth.floor(bb.minZ) >> SHIFT) & MASK);
	}

	/** After vanilla added {@code o} to the section. */
	public void add(Object o) {
		if (broken) {
			return;
		}
		if (!(o instanceof Entity entity)) {
			breakIndex();
			return;
		}
		Node node = ((Holder) entity).mcrs$gridNode();
		if (node.grid != null) {
			// Already in another section's index: not something vanilla does; stop indexing both.
			node.grid.breakIndex();
			breakIndex();
			return;
		}
		if (buckets == null) {
			buckets = new Node[MOD * MOD][];
			sizes = new int[MOD * MOD];
		}
		node.grid = this;
		node.seq = nextSeq++;
		AABB bb = entity.getBoundingBox();
		widen(bb);
		insert(node, bucketOf(bb));
		count++;
	}

	/** After vanilla removed {@code o} from the section. */
	public void remove(Object o) {
		if (broken) {
			return;
		}
		Node node = o instanceof Holder holder ? holder.mcrs$gridNode() : null;
		if (node == null || node.grid != this) {
			breakIndex();
			return;
		}
		erase(node);
		node.grid = null;
		if (--count == 0) {
			maxWidth = 0.0;
			maxDepth = 0.0;
		}
	}

	/** After {@code node}'s entity got a new box. */
	public static void moved(Node node, AABB bb) {
		EntityGrid grid = node.grid;
		if (grid != null) {
			grid.move(node, bb);
		}
	}

	private void move(Node node, AABB bb) {
		widen(bb);
		int bucket = bucketOf(bb);
		if (bucket != node.bucket) {
			erase(node);
			insert(node, bucket);
		}
	}

	private void widen(AABB bb) {
		double width = bb.maxX - bb.minX;
		double depth = bb.maxZ - bb.minZ;
		if (width > maxWidth) {
			maxWidth = width;
		}
		if (depth > maxDepth) {
			maxDepth = depth;
		}
	}

	private void insert(Node node, int bucket) {
		Node[] nodes = buckets[bucket];
		int size = sizes[bucket];
		if (nodes == null) {
			nodes = buckets[bucket] = new Node[4];
		} else if (size == nodes.length) {
			nodes = buckets[bucket] = Arrays.copyOf(nodes, size * 2);
		}
		nodes[size] = node;
		sizes[bucket] = size + 1;
		node.bucket = bucket;
		node.slot = size;
	}

	private void erase(Node node) {
		Node[] nodes = buckets[node.bucket];
		int last = --sizes[node.bucket];
		Node moved = nodes[last];
		nodes[node.slot] = moved;
		moved.slot = node.slot;
		nodes[last] = null;
	}

	private void breakIndex() {
		if (broken) {
			return;
		}
		broken = true;
		if (buckets != null) {
			for (int b = 0; b < buckets.length; b++) {
				for (int i = 0; i < sizes[b]; i++) {
					buckets[b][i].grid = null;
				}
			}
		}
		buckets = null;
		sizes = null;
	}

	/**
	 * Vanilla's {@code EntitySection.getEntities} over this section: all entities when
	 * {@code type} is null, else those of {@code type} (the section already holds some of its
	 * base class). Returns null when the caller should scan instead.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Continuation forEach(AABB bb, EntityTypeTest type, AbortableIterationConsumer consumer) {
		if (broken || count < MIN_SIZE) {
			return null;
		}
		double fromX = bb.minX - maxWidth - MARGIN;
		double fromZ = bb.minZ - maxDepth - MARGIN;
		if (!(bb.maxX - fromX < MAX_SPAN && bb.maxZ - fromZ < MAX_SPAN && Math.abs(bb.minX) < MAX_COORD && Math.abs(bb.minZ) < MAX_COORD)) {
			return null;
		}
		int x0 = Mth.floor(fromX) >> SHIFT;
		int x1 = Mth.floor(bb.maxX) >> SHIFT;
		int z0 = Mth.floor(fromZ) >> SHIFT;
		int z1 = Mth.floor(bb.maxZ) >> SHIFT;
		Class<?> base = type == null ? null : type.getBaseClass();
		Node[] hits = null;
		int n = 0;
		for (int cx = x0; cx <= x1; cx++) {
			int row = (cx & MASK) * MOD;
			for (int cz = z0; cz <= z1; cz++) {
				int bucket = row + (cz & MASK);
				Node[] nodes = buckets[bucket];
				for (int i = 0, size = sizes[bucket]; i < size; i++) {
					Node node = nodes[i];
					Entity entity = node.entity;
					if (entity.getBoundingBox().intersects(bb) && (base == null || base.isInstance(entity) && type.tryCast(entity) != null)) {
						if (hits == null) {
							hits = new Node[8];
						} else if (n == hits.length) {
							hits = Arrays.copyOf(hits, n * 2);
						}
						hits[n++] = node;
					}
				}
			}
		}
		if (n == 0) {
			return Continuation.CONTINUE;
		}
		if (n > 24) {
			Arrays.sort(hits, 0, n, BY_SEQ);
		} else {
			for (int i = 1; i < n; i++) {
				Node node = hits[i];
				int j = i - 1;
				while (j >= 0 && hits[j].seq > node.seq) {
					hits[j + 1] = hits[j];
					j--;
				}
				hits[j + 1] = node;
			}
		}
		for (int i = 0; i < n; i++) {
			// Tested again at vanilla's moment, in case an earlier consumer call moved it.
			Entity entity = hits[i].entity;
			if (!entity.getBoundingBox().intersects(bb)) {
				continue;
			}
			Object value = base == null ? entity : type.tryCast(entity);
			if (value != null && consumer.accept(value).shouldAbort()) {
				return Continuation.ABORT;
			}
		}
		return Continuation.CONTINUE;
	}
}
