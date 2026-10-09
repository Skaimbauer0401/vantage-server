package dev.mcrs.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.BoundedFloatFunction;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Serializes compiled density samplers (after vanilla's own optimizer and compiler)
 * for crates/mcrs-worldgen/src/density.rs.
 *
 * <p>Samplers are written generically: class simple name plus every record component
 * (or field), so Rust sees exactly what vanilla built. Shared nodes are written once
 * (the graph is a DAG). Floats and doubles are written as raw bits. Anything the
 * exporter can't represent throws {@link Unsupported}, and that world keeps using
 * vanilla's generator.
 */
final class WorldgenExport {
	static final class Unsupported extends RuntimeException {
		Unsupported(String message) {
			super(message);
		}
	}

	private final Map<Object, Integer> nodeIds = new IdentityHashMap<>();
	private final JsonArray nodes = new JsonArray();
	private final Map<Object, Integer> noiseIds = new IdentityHashMap<>();
	private final JsonArray noises = new JsonArray();

	JsonObject result(Map<String, DensitySampler> roots) {
		JsonObject rootIds = new JsonObject();
		roots.forEach((name, sampler) -> rootIds.addProperty(name, node(sampler)));
		JsonObject out = new JsonObject();
		out.add("roots", rootIds);
		out.add("nodes", nodes);
		out.add("noises", noises);
		return out;
	}

	int node(DensitySampler sampler) {
		Integer existing = nodeIds.get(sampler);
		if (existing != null) {
			return existing;
		}
		JsonObject json = new JsonObject();
		Class<?> type = sampler.getClass();
		json.addProperty("c", className(type));
		if (type.isRecord()) {
			for (RecordComponent component : type.getRecordComponents()) {
				json.add(component.getName(), value(read(component, sampler)));
			}
		} else {
			for (Field field : allFields(type)) {
				json.add(field.getName().replace("val$", ""), value(read(field, sampler)));
			}
		}
		int id = nodes.size();
		nodes.add(json);
		nodeIds.put(sampler, id);
		return id;
	}

	/** Nested class names like {@code BinaryFunction$AddSampler}; anonymous ones keep their {@code $1}. */
	private static String className(Class<?> type) {
		String name = type.getName();
		return name.substring(name.lastIndexOf('.') + 1);
	}

	private static List<Field> allFields(Class<?> type) {
		List<Field> fields = new ArrayList<>();
		for (Field field : type.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && !field.getName().equals("this$0")) {
				fields.add(field);
			}
		}
		return fields;
	}

	private static Object read(RecordComponent component, Object target) {
		try {
			var accessor = component.getAccessor();
			accessor.setAccessible(true);
			return accessor.invoke(target);
		} catch (ReflectiveOperationException e) {
			throw new Unsupported("cannot read " + component + ": " + e);
		}
	}

	private static Object read(Field field, Object target) {
		try {
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new Unsupported("cannot read " + field + ": " + e);
		}
	}

	private com.google.gson.JsonElement value(Object value) {
		JsonObject json = new JsonObject();
		switch (value) {
			case null -> json.addProperty("null", true);
			case DensitySampler sampler -> json.addProperty("node", node(sampler));
			case DensitySampler[] samplers -> {
				JsonArray array = new JsonArray();
				for (DensitySampler s : samplers) {
					array.add(node(s));
				}
				json.add("nodes", array);
			}
			case Noise noise -> json.addProperty("noise", noise(noise));
			case Float f -> json.addProperty("f", Float.floatToRawIntBits(f));
			case Double d -> json.addProperty("d", Double.doubleToRawLongBits(d));
			case Integer i -> json.addProperty("i", i);
			case Boolean b -> json.addProperty("b", b);
			case float[] floats -> {
				JsonArray array = new JsonArray();
				for (float f : floats) {
					array.add(Float.floatToRawIntBits(f));
				}
				json.add("fs", array);
			}
			case Enum<?> e -> json.addProperty("enum", e.name());
			case BoundedFloatFunction<?> function -> json.add("spline", spline(function));
			case net.minecraft.util.context.ContextKey<?> key -> json.addProperty("key", contextKey(key));
			case net.minecraft.util.Interval interval -> {
				json.addProperty("min", Float.floatToRawIntBits(interval.min()));
				json.addProperty("max", Float.floatToRawIntBits(interval.max()));
			}
			default -> throw new Unsupported("value of type " + value.getClass().getName());
		}
		return json;
	}

	private static String contextKey(net.minecraft.util.context.ContextKey<?> key) {
		if (key == Blender.ALPHA_KEY) {
			return "blend_alpha";
		}
		if (key == Blender.OFFSET_KEY) {
			return "blend_offset";
		}
		if (key == Beardifier.CONTEXT_KEY) {
			return "beardifier";
		}
		throw new Unsupported("context key " + key);
	}

	/** Spline samplers: {@code CubicSpline.asSampler} results and their coordinates. */
	private JsonObject spline(BoundedFloatFunction<?> function) {
		JsonObject json = new JsonObject();
		Class<?> type = function.getClass();
		// CubicSpline.asSampler(Multipoint) is an anonymous class capturing the spline.
		for (Field field : allFields(type)) {
			Object captured = read(field, function);
			if (captured instanceof CubicSpline<?> spline) {
				json.add("root", cubicSpline(spline));
				return json;
			}
		}
		// BoundedFloatFunction.constant(value): find its single float.
		for (Field field : allFields(type)) {
			Object captured = read(field, function);
			if (captured instanceof Float f) {
				JsonObject constant = new JsonObject();
				constant.addProperty("constant", Float.floatToRawIntBits(f));
				json.add("root", constant);
				return json;
			}
		}
		if (type.isRecord() && type.getSimpleName().equals("SamplerCoordinate")) {
			JsonObject coordinate = new JsonObject();
			for (RecordComponent component : type.getRecordComponents()) {
				coordinate.add(component.getName(), value(read(component, function)));
			}
			json.add("coordinate", coordinate);
			return json;
		}
		throw new Unsupported("spline function " + type.getName());
	}

	private JsonObject cubicSpline(CubicSpline<?> spline) {
		JsonObject json = new JsonObject();
		switch (spline) {
			case CubicSpline.Constant<?> constant -> json.addProperty("constant", Float.floatToRawIntBits(constant.value()));
			case CubicSpline.Multipoint<?> multipoint -> {
				json.add("coordinate", spline((BoundedFloatFunction<?>) multipoint.coordinate()));
				json.add("locations", value(multipoint.locations()));
				json.add("derivatives", value(multipoint.derivatives()));
				JsonArray values = new JsonArray();
				for (CubicSpline<?> v : multipoint.values()) {
					values.add(cubicSpline(v));
				}
				json.add("values", values);
			}
		}
		return json;
	}

	int noise(Noise noise) {
		Integer existing = noiseIds.get(noise);
		if (existing != null) {
			return existing;
		}
		JsonObject json = new JsonObject();
		switch (noise) {
			case SmearedPerlinNoise smeared -> {
				json.addProperty("type", "smeared");
				gradient(json, smeared);
				json.addProperty("fudge", Double.doubleToRawLongBits((double) read(field(SmearedPerlinNoise.class, "fudgeYScale"), smeared)));
			}
			case PerlinNoise perlin -> {
				json.addProperty("type", "perlin");
				gradient(json, perlin);
			}
			case SimplexNoise simplex -> {
				json.addProperty("type", "simplex");
				gradient(json, simplex);
			}
			case net.minecraft.world.level.levelgen.synth.NoiseStack stack -> {
				json.addProperty("type", "stack");
				Object[] layers = (Object[]) read(field(net.minecraft.world.level.levelgen.synth.NoiseStack.class, "layers"), stack);
				JsonArray array = new JsonArray();
				for (Object layer : layers) {
					JsonObject l = new JsonObject();
					for (RecordComponent component : layer.getClass().getRecordComponents()) {
						Object v = read(component, layer);
						switch (component.getName()) {
							case "noise" -> l.addProperty("noise", noise((Noise) v));
							case "frequency" -> l.addProperty("frequency", Double.doubleToRawLongBits((double) v));
							case "amplitude" -> l.addProperty("amplitude", Float.floatToRawIntBits((float) v));
							default -> throw new Unsupported("noise layer component " + component.getName());
						}
					}
					array.add(l);
				}
				json.add("layers", array);
			}
			default -> throw new Unsupported("noise " + noise.getClass().getName());
		}
		int id = noises.size();
		noises.add(json);
		noiseIds.put(noise, id);
		return id;
	}

	private void gradient(JsonObject json, Noise noise) {
		Class<?> base = net.minecraft.world.level.levelgen.synth.GradientNoise.class;
		byte[] perms = (byte[]) read(field(base, "perms"), noise);
		JsonArray p = new JsonArray();
		for (byte b : perms) {
			p.add(b & 0xFF);
		}
		json.add("perms", p);
		for (String axis : new String[] {"offsetX", "offsetY", "offsetZ"}) {
			json.addProperty(axis, Double.doubleToRawLongBits((double) read(field(base, axis), noise)));
		}
	}

	private static Field field(Class<?> type, String name) {
		try {
			return type.getDeclaredField(name);
		} catch (NoSuchFieldException e) {
			throw new Unsupported(type.getName() + "." + name + " missing");
		}
	}

	/** Seeds of a positional random factory, e.g. the aquifer's. */
	static JsonObject positional(PositionalRandomFactory factory) {
		JsonObject json = new JsonObject();
		switch (factory) {
			case XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro -> {
				json.addProperty("type", "xoroshiro");
				json.addProperty("lo", (long) read(field(xoroshiro.getClass(), "seedLo"), xoroshiro));
				json.addProperty("hi", (long) read(field(xoroshiro.getClass(), "seedHi"), xoroshiro));
			}
			case LegacyRandomSource.LegacyPositionalRandomFactory legacy -> {
				json.addProperty("type", "legacy");
				json.addProperty("seed", (long) read(field(legacy.getClass(), "seed"), legacy));
			}
			default -> throw new Unsupported("positional random " + factory.getClass().getName());
		}
		return json;
	}
}
