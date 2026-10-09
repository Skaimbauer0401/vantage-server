package dev.mcrs.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.condition.AbovePreliminarySurfaceCondition;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.HoleCondition;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NoiseThresholdCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.condition.SteepCondition;
import net.minecraft.world.level.levelgen.material.condition.StoneDepthCondition;
import net.minecraft.world.level.levelgen.material.condition.TemperatureCondition;
import net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition;
import net.minecraft.world.level.levelgen.material.condition.WaterCondition;
import net.minecraft.world.level.levelgen.material.condition.YCondition;
import net.minecraft.world.level.levelgen.material.rule.BandlandsRule;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.Noise;

/**
 * Exports the surface step ({@code MaterialSystem}): its rule tree, noises, clay
 * bands, biome climate and per-block-state flags, for crates/mcrs-worldgen/src/surface.rs.
 * Rule references are inlined, so every reference is compiled separately like vanilla.
 */
final class SurfaceExport {
	/** Per-state flag bits, see STATE_* in surface.rs. */
	static final int AIR = 1, HAS_FLUID = 2, BLOCKS_MOTION = 4, WORLD_SURFACE = 8, WATER = 16, LAVA = 32;
	static final int COUNT_NON_EMPTY = 128, COUNT_FLUID = 256, COUNT_TICKING_BLOCK = 512, COUNT_TICKING_FLUID = 1024;

	private final WorldgenExport exporter;
	private final RandomState randomState;

	SurfaceExport(WorldgenExport exporter, RandomState randomState) {
		this.exporter = exporter;
		this.randomState = randomState;
	}

	/** Flags for every block state id (the defaultBlock bit is added in Rust from the export). */
	static JsonArray stateFlags(IdMap<BlockState> ids) {
		int[] flags = new int[ids.size()];
		for (BlockState state : ids) {
			int f = 0;
			if (state.isAir()) {
				f |= AIR;
			}
			if (!state.getFluidState().isEmpty()) {
				f |= HAS_FLUID;
			}
			if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
				f |= BLOCKS_MOTION;
			}
			if (Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(state)) {
				f |= WORLD_SURFACE;
			}
			if (state.is(Blocks.WATER)) {
				f |= WATER;
			}
			if (state.is(Blocks.LAVA)) {
				f |= LAVA;
			}
			int counts = ChunkIo.stateFlags(state);
			f |= (counts & 1) != 0 ? COUNT_NON_EMPTY : 0;
			f |= (counts & 2) != 0 ? COUNT_TICKING_BLOCK : 0;
			f |= (counts & 4) != 0 ? COUNT_FLUID : 0;
			f |= (counts & 8) != 0 ? COUNT_TICKING_FLUID : 0;
			flags[ids.getId(state)] = f;
		}
		JsonArray array = new JsonArray();
		for (int f : flags) {
			array.add(f);
		}
		return array;
	}

	JsonObject surface(NoiseGeneratorSettings settings, IdMap<Holder<Biome>> biomeIds) {
		this.biomeIds = biomeIds;
		MaterialSystem system = randomState.surfaceSystem();
		JsonObject json = new JsonObject();
		json.add("rule", rule(settings.materialRule().value()));
		json.addProperty("preliminary_surface", exporter.node(randomState.getSampler(system.preliminarySurfaceFunction())));
		JsonObject noises = new JsonObject();
		for (String field : new String[] {"surfaceNoise", "surfaceSecondaryNoise", "clayBandsOffsetNoise", "badlandsPillarNoise",
				"badlandsPillarRoofNoise", "badlandsSurfaceNoise", "icebergPillarNoise", "icebergPillarRoofNoise", "icebergSurfaceNoise"}) {
			noises.addProperty(field, exporter.noise((Noise) read(MaterialSystem.class, field, system)));
		}
		noises.addProperty("temperature", exporter.noise((Noise) read(Biome.class, "TEMPERATURE_NOISE", null)));
		noises.addProperty("frozen_temperature", exporter.noise(Biome.FROZEN_TEMPERATURE_NOISE));
		noises.addProperty("biome_info", exporter.noise((Noise) read(Biome.class, "BIOME_INFO_NOISE", null)));
		json.add("noises", noises);
		json.add("noise_random", WorldgenExport.positional((PositionalRandomFactory) read(MaterialSystem.class, "noiseRandom", system)));
		JsonArray bands = new JsonArray();
		for (BlockState state : (BlockState[]) read(MaterialSystem.class, "clayBands", system)) {
			bands.add(Block.getId(state));
		}
		json.add("clay_bands", bands);
		json.addProperty("snow_block", Block.getId(Blocks.SNOW_BLOCK.defaultBlockState()));
		json.addProperty("packed_ice", Block.getId(Blocks.PACKED_ICE.defaultBlockState()));
		json.addProperty("zoom_seed", BiomeManager.obfuscateSeed(randomState.seed()));
		JsonArray defaultStates = new JsonArray();
		for (BlockState state : settings.defaultBlock().getBlock().getStateDefinition().getPossibleStates()) {
			defaultStates.add(Block.getId(state));
		}
		json.add("default_block_states", defaultStates);

		JsonArray biomes = new JsonArray();
		for (Holder<Biome> holder : biomeIds) {
			Biome biome = holder.value();
			Object climate = read(Biome.class, "climateSettings", biome);
			JsonObject b = new JsonObject();
			b.addProperty("id", biomeIds.getId(holder));
			b.addProperty("temperature", Float.floatToRawIntBits(biome.getBaseTemperature()));
			b.addProperty("frozen", readRecord(climate, "temperatureModifier") == Biome.TemperatureModifier.FROZEN);
			b.addProperty("eroded_badlands", holder.is(Biomes.ERODED_BADLANDS));
			b.addProperty("frozen_ocean", holder.is(Biomes.FROZEN_OCEAN) || holder.is(Biomes.DEEP_FROZEN_OCEAN));
			biomes.add(b);
		}
		json.add("biomes", biomes);
		return json;
	}

	private IdMap<Holder<Biome>> biomeIds;

	private JsonObject rule(MaterialRule rule) {
		JsonObject json = new JsonObject();
		switch (rule) {
			case MaterialRule.HolderHolder(var holder) -> {
				return rule(holder.value());
			}
			case SequenceRule sequence -> {
				json.addProperty("t", "sequence");
				JsonArray rules = new JsonArray();
				for (MaterialRule r : sequence.sequence()) {
					rules.add(rule(r));
				}
				json.add("rules", rules);
			}
			case ConditionRule condition -> {
				json.addProperty("t", "condition");
				json.add("if", condition(condition.ifTrue()));
				json.add("then", rule(condition.thenRun()));
			}
			case BlockRule block -> {
				json.addProperty("t", "block");
				json.addProperty("state", Block.getId(block.resultState()));
			}
			case BandlandsRule ignored -> json.addProperty("t", "bandlands");
			case OreVeinRule ore -> {
				json.addProperty("t", "ore_vein");
				json.addProperty("ore", Block.getId(ore.oreBlock()));
				json.addProperty("raw_ore", Block.getId(ore.rawOreBlock()));
				json.addProperty("filler", Block.getId(ore.fillerBlock()));
				json.addProperty("raw_ore_chance", Float.floatToRawIntBits(ore.rawOreChance()));
				json.addProperty("density", exporter.node(randomState.getSampler(ore.density())));
				json.addProperty("richness", exporter.node(randomState.getSampler(ore.richness())));
				json.addProperty("filler_gap", exporter.node(randomState.getSampler(ore.fillerGap())));
				json.add("random", WorldgenExport.positional(randomState.getOrCreateRandomFactory(Identifier.withDefaultNamespace("ore"))));
			}
			default -> throw new WorldgenExport.Unsupported("material rule " + rule.getClass().getName());
		}
		return json;
	}

	private JsonObject condition(MaterialCondition condition) {
		JsonObject json = new JsonObject();
		switch (condition) {
			case MaterialCondition.HolderHolder(var holder) -> {
				return condition(holder.value());
			}
			case BiomeCondition biome -> {
				json.addProperty("t", "biome");
				JsonArray ids = new JsonArray();
				for (Holder<Biome> b : biome.biomes()) {
					ids.add(biomeIds.getId(b));
				}
				json.add("biomes", ids);
			}
			case NoiseThresholdCondition noise -> {
				json.addProperty("t", "noise_threshold");
				json.addProperty("noise", exporter.noise(randomState.getOrCreateNoise(noise.noise())));
				json.addProperty("min", Double.doubleToRawLongBits(noise.minThreshold()));
				json.addProperty("max", Double.doubleToRawLongBits(noise.maxThreshold()));
				json.addProperty("is3d", noise.is3d());
			}
			case VerticalGradientCondition gradient -> {
				json.addProperty("t", "vertical_gradient");
				json.add("random", WorldgenExport.positional(randomState.getOrCreateRandomFactory(gradient.randomName())));
				json.add("true_at", anchor(gradient.trueAtAndBelow()));
				json.add("false_at", anchor(gradient.falseAtAndAbove()));
			}
			case YCondition y -> {
				json.addProperty("t", "y_above");
				json.add("anchor", anchor(y.anchor()));
				json.addProperty("mult", y.surfaceDepthMultiplier());
				json.addProperty("add_stone_depth", y.addStoneDepth());
			}
			case WaterCondition water -> {
				json.addProperty("t", "water");
				json.addProperty("offset", water.offset());
				json.addProperty("mult", water.surfaceDepthMultiplier());
				json.addProperty("add_stone_depth", water.addStoneDepth());
			}
			case TemperatureCondition ignored -> json.addProperty("t", "temperature");
			case SteepCondition ignored -> json.addProperty("t", "steep");
			case HoleCondition ignored -> json.addProperty("t", "hole");
			case AbovePreliminarySurfaceCondition ignored -> json.addProperty("t", "above_preliminary_surface");
			case NotCondition not -> {
				json.addProperty("t", "not");
				json.add("cond", condition(not.target()));
			}
			case StoneDepthCondition depth -> {
				json.addProperty("t", "stone_depth");
				json.addProperty("offset", depth.offset());
				json.addProperty("add_surface_depth", depth.addSurfaceDepth());
				json.addProperty("secondary_range", depth.secondaryDepthRange());
				json.addProperty("ceiling", depth.surfaceType() == CaveSurface.CEILING);
			}
			default -> throw new WorldgenExport.Unsupported("material condition " + condition.getClass().getName());
		}
		return json;
	}

	private static JsonObject anchor(VerticalAnchor anchor) {
		JsonObject json = new JsonObject();
		switch (anchor) {
			case VerticalAnchor.Absolute(int y) -> {
				json.addProperty("kind", "absolute");
				json.addProperty("v", y);
			}
			case VerticalAnchor.AboveBottom(int offset) -> {
				json.addProperty("kind", "above_bottom");
				json.addProperty("v", offset);
			}
			case VerticalAnchor.BelowTop(int offset) -> {
				json.addProperty("kind", "below_top");
				json.addProperty("v", offset);
			}
			case VerticalAnchor.RelativeToSeaLevel(int offset) -> {
				json.addProperty("kind", "relative_to_sea_level");
				json.addProperty("v", offset);
			}
			default -> throw new WorldgenExport.Unsupported("vertical anchor " + anchor);
		}
		return json;
	}

	private static Object read(Class<?> type, String name, Object target) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new WorldgenExport.Unsupported(type.getSimpleName() + "." + name + ": " + e);
		}
	}

	private static Object readRecord(Object record, String component) {
		try {
			for (var c : record.getClass().getRecordComponents()) {
				if (c.getName().equals(component)) {
					var accessor = c.getAccessor();
					accessor.setAccessible(true);
					return accessor.invoke(record);
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new WorldgenExport.Unsupported("climate " + component + ": " + e);
		}
		throw new WorldgenExport.Unsupported("climate " + component + " missing");
	}
}
