package dev.mcrs.bridge;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/**
 * Terrain generation through Rust (crates/mcrs-worldgen). One export per
 * {@link RandomState} (a dimension of a running server).
 *
 * <p>System properties: {@code mcrs.worldgen=false} disables it,
 * {@code mcrs.worldgen.dump=true} writes each export to {@code mcrs-worldgen-*.json}.
 */
public final class Worldgen {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.worldgen"));
	public static final boolean DUMP = Boolean.getBoolean("mcrs.worldgen.dump");
	/** {@code mcrs.worldgen.surface=false} keeps the surface step on vanilla. */
	public static final boolean SURFACE = !"false".equals(System.getProperty("mcrs.worldgen.surface"));

	/** Export result per RandomState; null value = not supported, use vanilla. */
	private static final Map<RandomState, JsonObject> EXPORTS = new WeakHashMap<>();

	private Worldgen() {
	}

	public static synchronized JsonObject export(RandomState randomState, NoiseGeneratorSettings settings,
			net.minecraft.core.IdMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeIds,
			net.minecraft.world.level.biome.BiomeSource biomeSource) {
		if (EXPORTS.containsKey(randomState)) {
			return EXPORTS.get(randomState);
		}
		JsonObject json = null;
		try {
			json = build(randomState, settings, biomeIds, biomeSource);
			if (DUMP) {
				Path file = Path.of("mcrs-worldgen-" + EXPORTS.size() + ".json");
				Files.writeString(file, json.toString());
				McrsBridge.LOGGER.info("mcrs: wrote worldgen export to {}", file.toAbsolutePath());
			}
		} catch (WorldgenExport.Unsupported e) {
			McrsBridge.LOGGER.warn("mcrs: terrain stays on vanilla for this dimension: {}", e.getMessage());
		} catch (IOException e) {
			McrsBridge.LOGGER.warn("mcrs: could not dump worldgen export", e);
		}
		EXPORTS.put(randomState, json);
		return json;
	}

	private static JsonObject build(RandomState randomState, NoiseGeneratorSettings settings,
			net.minecraft.core.IdMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeIds,
			net.minecraft.world.level.biome.BiomeSource biomeSource) {
		Map<String, DensitySampler> roots = new LinkedHashMap<>();
		roots.put("final_density", randomState.getSampler(settings.noiseRouter().finalDensity()));
		// Climate.Sampler of NoiseRouter.createClimateSampler, for biomes.
		var router = settings.noiseRouter();
		roots.put("climate_temperature", randomState.getSampler(router.temperature()));
		roots.put("climate_humidity", randomState.getSampler(router.vegetation()));
		roots.put("climate_continentalness", randomState.getSampler(router.continents()));
		roots.put("climate_erosion", randomState.getSampler(router.erosion()));
		roots.put("climate_depth", randomState.getSampler(router.depth()));
		roots.put("climate_weirdness", randomState.getSampler(router.ridges()));
		settings.aquifers().ifPresent(aquifer -> {
			roots.put("barrier", randomState.getSampler(aquifer.barrierNoise()));
			roots.put("fluid_level_floodedness", randomState.getSampler(aquifer.fluidLevelFloodednessNoise()));
			roots.put("fluid_level_spread", randomState.getSampler(aquifer.fluidLevelSpreadNoise()));
			roots.put("lava", randomState.getSampler(aquifer.lavaNoise()));
			roots.put("exclusion", randomState.getSampler(aquifer.exclusion()));
			roots.put("surface_level", randomState.getSampler(aquifer.surfaceLevel()));
		});
		WorldgenExport exporter = new WorldgenExport();
		JsonObject json = exporter.result(roots);
		json.addProperty("aquifers", settings.aquifers().isPresent());
		if (settings.aquifers().isPresent()) {
			json.add("aquifer_random", WorldgenExport.positional(randomState.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"))));
		}
		json.addProperty("sea_level", settings.seaLevel());
		json.addProperty("default_block", Block.getId(settings.defaultBlock()));
		json.addProperty("default_fluid", Block.getId(settings.defaultFluid()));
		json.addProperty("default_block_name", BuiltInRegistries.BLOCK.getKey(settings.defaultBlock().getBlock()).toString());
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState lava = Blocks.LAVA.defaultBlockState();
		json.addProperty("air", Block.getId(air));
		json.addProperty("lava", Block.getId(lava));
		JsonObject states = new JsonObject();
		for (BlockState state : new BlockState[] {air, lava, settings.defaultBlock(), settings.defaultFluid()}) {
			states.add(Integer.toString(Block.getId(state)), stateInfo(state));
		}
		json.add("states", states);
		json.add("beard_kernel", beardKernel());
		json.add("state_flags", SurfaceExport.stateFlags(Block.BLOCK_STATE_REGISTRY));
		if (Biomes.ENABLED && biomeSource instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource multiNoise) {
			try {
				json.add("biome_tree", Biomes.exportTree(multiNoise, biomeIds));
			} catch (ReflectiveOperationException | RuntimeException e) {
				McrsBridge.LOGGER.warn("mcrs: biomes stay on vanilla for this dimension: {}", e.toString());
			}
		}
		if (SURFACE) {
			try {
				json.add("surface", new SurfaceExport(exporter, randomState).surface(settings, biomeIds));
			} catch (WorldgenExport.Unsupported e) {
				McrsBridge.LOGGER.warn("mcrs: surface stays on vanilla for this dimension: {}", e.getMessage());
			}
		}
		return json;
	}

	/** {@code Beardifier.BEARD_KERNEL} as float bits ({@code {"fs": [...]}}). */
	private static JsonObject beardKernel() {
		try {
			var field = net.minecraft.world.level.levelgen.Beardifier.class.getDeclaredField("BEARD_KERNEL");
			field.setAccessible(true);
			float[] kernel = (float[]) field.get(null);
			com.google.gson.JsonArray values = new com.google.gson.JsonArray();
			for (float f : kernel) {
				values.add(Float.floatToRawIntBits(f));
			}
			JsonObject json = new JsonObject();
			json.add("fs", values);
			return json;
		} catch (ReflectiveOperationException e) {
			throw new WorldgenExport.Unsupported("Beardifier.BEARD_KERNEL: " + e);
		}
	}

	/** What terrain filling needs to know about a block state, from vanilla's own predicates. */
	private static JsonObject stateInfo(BlockState state) {
		JsonObject info = new JsonObject();
		info.addProperty("air", state.isAir());
		info.addProperty("water", state.is(Blocks.WATER));
		info.addProperty("lava", state.is(Blocks.LAVA));
		info.addProperty("has_fluid", !state.getFluidState().isEmpty());
		info.addProperty("blocks_motion", Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state));
		info.addProperty("world_surface", Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(state));
		int flags = ChunkIo.stateFlags(state);
		info.addProperty("count_non_empty", (flags & 1) != 0);
		info.addProperty("count_ticking_block", (flags & 2) != 0);
		info.addProperty("count_fluid", (flags & 4) != 0);
		info.addProperty("count_ticking_fluid", (flags & 8) != 0);
		return info;
	}
}
