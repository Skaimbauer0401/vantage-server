package dev.mcrs.bridge;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/**
 * Carving with Rust: Java computes vanilla's carving mask (the tunnels and canyons, which only
 * depend on the seed and the biomes), Rust applies it inside the terrain fill, right after the
 * surface, with its aquifer and surface rules ({@code applyCarvingMask}: replace blocks, fluid
 * post-processing, top material under grass). The Java carving step then skips the chunk.
 *
 * <p>Exact. {@code -Dmcrs.carvers=false} keeps carving on vanilla. With
 * {@code -Dmcrs.worldgen.verify=true} the comparison runs after vanilla's carvers.
 */
public final class Carvers {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.carvers"));
	public static final AtomicLong CARVED = new AtomicLong();
	/** Chunks whose carving Rust applied; vanilla's generateCarvers skips them. */
	public static final java.util.Set<ChunkAccess> DONE = ConcurrentHashMap.newKeySet();
	private static final Field MASK_BITS;

	static {
		try {
			MASK_BITS = CarvingMask.class.getDeclaredField("mask");
			MASK_BITS.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private Carvers() {
	}

	/** The mask as wg_fill ints: [1, minY, maxY, words, (lo, hi) per word]; [0] for none. */
	public static int[] maskInts(NoiseBasedChunkGenerator generator, ChunkAccess chunk, RandomState randomState, WorldGenRegion region) {
		CarvingMask mask = mask(generator, chunk, randomState, region);
		long[] words;
		try {
			words = ((BitSet) MASK_BITS.get(mask)).toLongArray();
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		int[] out = new int[4 + 2 * words.length];
		out[0] = 1;
		out[1] = mask.minY();
		out[2] = mask.maxY();
		out[3] = words.length;
		for (int i = 0; i < words.length; i++) {
			out[4 + 2 * i] = (int) words[i];
			out[5 + 2 * i] = (int) (words[i] >>> 32);
		}
		return out;
	}

	/** The first half of {@code NoiseBasedChunkGenerator.generateCarvers}, unchanged. */
	private static CarvingMask mask(NoiseBasedChunkGenerator generator, ChunkAccess chunk, RandomState randomState, WorldGenRegion carverBiomeRegion) {
		BiomeResolver biomeResolver = generator.getBiomeSource().createUncachedResolver(randomState);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		ChunkPos pos = chunk.getPos();
		WorldGenerationContext context = new WorldGenerationContext(generator, chunk.getHeightAccessorForGeneration());
		int protectedBlocksOnTop = chunk.isUpgrading() ? 0 : 7;
		int maxY = context.getMinGenY() + context.getGenDepth() - 1 - protectedBlocksOnTop;
		CarvingMask mask = new CarvingMask(context.getMinGenY() + 1, maxY);
		dev.mcrs.bridge.mixin.NoiseBasedChunkGeneratorInvoker invoker = (dev.mcrs.bridge.mixin.NoiseBasedChunkGeneratorInvoker) (Object) generator;
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				ChunkPos sourcePos = new ChunkPos(pos.x() + dx, pos.z() + dz);
				ChunkAccess carverCenterChunk = carverBiomeRegion.getChunk(sourcePos.x(), sourcePos.z());
				BiomeGenerationSettings settings = carverCenterChunk.carverBiome(() -> invoker.mcrs$getBiomeGenerationSettingsForCarver(biomeResolver, sourcePos));
				int index = 0;
				for (Holder<WorldCarver> carverHolder : settings.getCarvers()) {
					WorldCarver carver = carverHolder.value();
					random.setLargeFeatureSeed(randomState.seed() + index, sourcePos.x(), sourcePos.z());
					if (carver.isStartChunk(random)) {
						carver.carve(context, random, chunk.getPos(), sourcePos, mask);
					}
					index++;
				}
			}
		}
		return mask;
	}
}
