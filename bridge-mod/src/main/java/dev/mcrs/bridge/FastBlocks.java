package dev.mcrs.bridge;

/**
 * A block state cache for {@code Level.getBlockState} on the server thread, for the
 * repeated neighbor reads of redstone and block updates; {@code -Dmcrs.fastBlocks=false}
 * turns it off.
 *
 * <p>It only ever returns what the chunk holds: every block change of a loaded chunk goes
 * through {@code LevelChunk.setBlockState}, which drops the position before and after it
 * writes (block callbacks inside it may read the position). Any full chunk status change and
 * any chunk unload clears the whole cache, so a cached answer never skips the chunk lookup
 * (and the loading or ticket it would cause) for a chunk that is not fully loaded.
 *
 * <p>A server level's {@code BiomeManager.getBiome} is cached the same way (cleared on chunk
 * loads, unloads, status changes and biome refills).
 *
 * <p>Collision iteration ({@code BlockCollisions}) reads blocks of plain level chunks straight
 * from their sections.
 */
public final class FastBlocks {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.fastBlocks"));

	private FastBlocks() {
	}

	/** Biome cache verify counters (-Dmcrs.biomeCache.verify=true). */
	public static final java.util.concurrent.atomic.AtomicLong BIOME_VERIFIED = new java.util.concurrent.atomic.AtomicLong();
	public static final java.util.concurrent.atomic.AtomicLong BIOME_MISMATCHED = new java.util.concurrent.atomic.AtomicLong();

	/** Implemented by the biome manager mixin. */
	public interface BiomeCache {
		void mcrs$clearBiomeCache();
	}

	/** Implemented by the level mixin. */
	public interface Cache {
		void mcrs$invalidate(long pos);

		void mcrs$clearBlockCache();
	}
}
