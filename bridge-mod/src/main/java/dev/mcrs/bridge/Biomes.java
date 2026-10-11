package dev.mcrs.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Biome generation ({@code ChunkGenerator.doCreateBiomes}) through Rust: the six climate
 * density functions are sampled on the same 4x4x4 grid and the biome is looked up in vanilla's
 * own R-tree (exported as built), with the same search and the same per-thread last result.
 * The chunk's biome containers are then filled by vanilla's {@code fillBiomesFromNoise} with a
 * resolver that reads Rust's answers, so palettes come out in vanilla's order.
 *
 * <p>Exact, also in the performance edition (Rust samples the climate with an exact copy of
 * the noises there). {@code -Dmcrs.biomes=false} restores vanilla;
 * {@code -Dmcrs.biomes.verify=true} also runs vanilla's resolver and compares every biome.
 */
public final class Biomes {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.biomes"));
	public static final boolean VERIFY = Boolean.getBoolean("mcrs.biomes.verify");
	public static final AtomicLong FILLED = new AtomicLong();
	public static final AtomicLong VERIFIED = new AtomicLong();
	public static final AtomicLong MISMATCHED = new AtomicLong();
	public static final AtomicLong TIES = new AtomicLong();

	private Biomes() {
	}

	/** Fills the chunk's biomes through Rust; false means vanilla must do it. */
	public static boolean tryFill(ChunkGenerator generator, Blender blender, RandomState randomState, ChunkAccess chunk) {
		if (!ENABLED || !Worldgen.ENABLED || !NativeBridge.isAvailable() || !(generator instanceof NoiseBasedChunkGenerator noise)
				|| !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource)) {
			return false;
		}
		long handle = TerrainFill.handle(randomState, noise.generatorSettings().value(), chunk, generator);
		if (handle == 0) {
			return false;
		}
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		int quartsY = QuartPos.fromBlock(chunk.getHeight());
		int[] ids = NativeBridge.wgBiomes(handle, minX, minY, minZ, quartsY);
		if (ids == null) {
			return false;
		}
		IdMap<Holder<Biome>> biomeIds = TerrainFill.biomeIds(chunk);
		int minQuartX = QuartPos.fromBlock(minX);
		int minQuartY = QuartPos.fromBlock(minY);
		int minQuartZ = QuartPos.fromBlock(minZ);
		BiomeResolver rust = (quartX, quartY, quartZ) -> biomeIds.byIdOrThrow(ids[((quartY - minQuartY) * 4 + (quartZ - minQuartZ)) * 4 + (quartX - minQuartX)]);
		BiomeResolver resolver = rust;
		if (VERIFY) {
			Climate.Sampler climate = randomState.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
			MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) generator.getBiomeSource();
			BiomeResolver vanilla = source.createResolver(climate);
			resolver = (quartX, quartY, quartZ) -> {
				Holder<Biome> mine = rust.getNoiseBiome(quartX, quartY, quartZ);
				Holder<Biome> theirs = vanilla.getNoiseBiome(quartX, quartY, quartZ);
				if (mine == theirs) {
					VERIFIED.incrementAndGet();
				} else {
					// Equal distance (a tie): vanilla's own answer then depends on the thread's
					// previous search, so both are vanilla results.
					Climate.TargetPoint target = climate.sample(quartX, quartY, quartZ);
					if (fitness(source, mine, target) == fitness(source, theirs, target)) {
						TIES.incrementAndGet();
					} else if (MISMATCHED.incrementAndGet() <= 5) {
						McrsBridge.LOGGER.warn("mcrs: biome mismatch at quart {} {} {}: rust {} vanilla {}", quartX, quartY, quartZ, mine.getRegisteredName(),
								theirs.getRegisteredName());
					}
				}
				return theirs;
			};
		}
		chunk.fillBiomesFromNoise(((dev.mcrs.bridge.mixin.ChunkGeneratorInvoker) generator).mcrs$decorateBiomeResolver(blender, chunk, resolver));
		FILLED.incrementAndGet();
		return true;
	}

	/** The smallest fitness of the biome's parameter points for the target. */
	@SuppressWarnings("unchecked")
	private static long fitness(MultiNoiseBiomeSource source, Holder<Biome> biome, Climate.TargetPoint target) {
		try {
			Method parameters = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
			parameters.setAccessible(true);
			Climate.ParameterList<Holder<Biome>> list = (Climate.ParameterList<Holder<Biome>>) parameters.invoke(source);
			long best = Long.MAX_VALUE;
			for (var entry : list.values()) {
				if (entry.getSecond() == biome) {
					best = Math.min(best, entry.getFirst().fitness(target));
				}
			}
			return best;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	/** Vanilla's built R-tree of the biome source: {"p": [min, max] x 7, "c": [children]} or {"p", "b": biome id}. */
	static JsonObject exportTree(MultiNoiseBiomeSource source, IdMap<Holder<Biome>> biomeIds) throws ReflectiveOperationException {
		Method parameters = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
		parameters.setAccessible(true);
		Object list = parameters.invoke(source);
		Field index = Climate.ParameterList.class.getDeclaredField("index");
		index.setAccessible(true);
		Object tree = index.get(list);
		Field root = tree.getClass().getDeclaredField("root");
		root.setAccessible(true);
		return node(root.get(tree), biomeIds);
	}

	@SuppressWarnings("unchecked")
	private static JsonObject node(Object node, IdMap<Holder<Biome>> biomeIds) throws ReflectiveOperationException {
		Class<?> nodeClass = Class.forName("net.minecraft.world.level.biome.Climate$RTree$Node");
		Field space = nodeClass.getDeclaredField("parameterSpace");
		space.setAccessible(true);
		JsonObject json = new JsonObject();
		JsonArray p = new JsonArray();
		for (Climate.Parameter parameter : (Climate.Parameter[]) space.get(node)) {
			p.add(parameter.min());
			p.add(parameter.max());
		}
		json.add("p", p);
		String kind = node.getClass().getSimpleName();
		if (kind.equals("Leaf")) {
			Field value = node.getClass().getDeclaredField("value");
			value.setAccessible(true);
			int id = biomeIds.getId((Holder<Biome>) value.get(node));
			if (id < 0) {
				throw new IllegalStateException("biome without id: " + value.get(node));
			}
			json.addProperty("b", id);
		} else if (kind.equals("SubTree")) {
			Field children = node.getClass().getDeclaredField("children");
			children.setAccessible(true);
			JsonArray c = new JsonArray();
			for (Object child : (Object[]) children.get(node)) {
				c.add(node(child, biomeIds));
			}
			json.add("c", c);
		} else {
			throw new IllegalStateException("R-tree node " + node.getClass());
		}
		return json;
	}
}
