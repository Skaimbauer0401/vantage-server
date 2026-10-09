import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Runs vanilla Minecraft code and writes reference values for the Rust worldgen port.
 *
 * <p>Each suite writes {@code <out>/<suite>.txt}; the matching Rust test in
 * crates/mcrs-worldgen/tests performs the same calls and must produce identical
 * lines. Doubles and floats are written as raw bits so any difference shows.
 *
 * <p>Usage: {@code java -cp <server classpath>:oracle Oracle <out dir>}, see tools/oracle/run.sh.
 */
public final class Oracle {
	private final PrintWriter out;

	private Oracle(PrintWriter out) {
		this.out = out;
	}

	public static void main(String[] args) throws IOException {
		Path dir = Path.of(args[0]);
		Files.createDirectories(dir);
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		suite(dir, "random", Oracle::random);
		suite(dir, "math", Oracle::math);
		suite(dir, "noise", Oracle::noise);
	}

	/** Same formula in the Rust test; some points lie beyond the noise wrap range. */
	static double[] point(int i) {
		double x = (i * 7919 % 2003 - 1001) * 0.37 + i * 0.0013;
		double y = (i * 104729 % 389 - 64) * 0.91;
		double z = (i * 7907 % 1999 - 999) * 0.53 - i * 0.0007;
		if (i % 10 == 0) {
			x *= 99991.0;
			z *= 49999.0;
		}
		return new double[] {x, y, z};
	}

	static final int POINTS = 200;
	static final net.minecraft.world.level.levelgen.densityfunction.DensityVolume VOLUME =
			new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(4, 6, 3, -17, -64, 33, 2, 8, 4);

	void sampleNoise(String p, net.minecraft.world.level.levelgen.synth.Noise noise) {
		for (int i = 0; i < POINTS; i++) {
			double[] q = point(i);
			f(p + ".get3[" + i + "]", noise.get(q[0], q[1], q[2]));
			f(p + ".get2[" + i + "]", noise.get(q[0], q[2]));
		}
		var buffer = net.minecraft.world.level.levelgen.densityfunction.DensityBuffer.createUnpooled(VOLUME.size());
		noise.addToVolume(buffer, VOLUME, 0.03, 0.07, 0.75F);
		noise.addToVolume(buffer, VOLUME, 1.7, 0.4, -1.25F);
		for (int i = 0; i < VOLUME.size(); i++) {
			f(p + ".volume[" + i + "]", buffer.get(i));
		}
	}

	static void noise(Oracle o) {
		for (long seed : new long[] {0L, 42L, -123456789L}) {
			String s = "[" + seed + "]";
			o.sampleNoise("perlin" + s, new net.minecraft.world.level.levelgen.synth.PerlinNoise(new XoroshiroRandomSource(seed)));
			o.sampleNoise("perlinLegacy" + s, new net.minecraft.world.level.levelgen.synth.PerlinNoise(new LegacyRandomSource(seed)));
			o.sampleNoise("smeared" + s, new net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise(new XoroshiroRandomSource(seed), 4.2));
			o.sampleNoise("simplex" + s, new net.minecraft.world.level.levelgen.synth.SimplexNoise(new XoroshiroRandomSource(seed)));
			var normal = net.minecraft.world.level.levelgen.synth.NormalNoise.builder().setBaseOctave(-7).setOctaveCount(3).build();
			o.sampleNoise("normal" + s, normal.create(new XoroshiroRandomSource(seed)));
			o.sampleNoise("normalLegacyRandom" + s, normal.create(new LegacyRandomSource(seed)));
			var parity = net.minecraft.world.level.levelgen.synth.NormalNoise.createParity(-9, 1.0, 0.0, 3.0, 1.5);
			o.sampleNoise("parity" + s, parity.create(new XoroshiroRandomSource(seed)));
			var legacy = net.minecraft.world.level.levelgen.synth.NormalNoise.builder()
					.setBaseOctave(-4).setOctaveCount(4).setLegacyNormalization().setAmplitudeModifier(1, 0.5).build();
			o.sampleNoise("legacyNorm" + s, legacy.create(new XoroshiroRandomSource(seed)));
			o.sampleNoise("fbm" + s, net.minecraft.world.level.levelgen.synth.BlendedNoise.createFbm(
					new XoroshiroRandomSource(seed), -15, 684.412 * 0.125 * 8.0, 0.99998474F));
		}
	}

	/**
	 * {@code <fn> <input bits...> <output bits>}. Uses {@code Math}, which equals
	 * StrictMath (fdlibm) only when run with -XX:-UseLibmIntrinsic, like mcrs does.
	 */
	static void math(Oracle o) {
		java.util.SplittableRandom r = new java.util.SplittableRandom(7);
		java.util.List<Double> special = new java.util.ArrayList<>(java.util.List.of(
				0.0, -0.0, 1.0, -1.0, 0.5, 2.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
				Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Math.PI,
				Math.PI / 2, Math.PI / 4, 3 * Math.PI / 4, 1e-300, 1e-10, 1e10, 1e22, 1e300, 709.78, -745.13, 710.0,
				-746.0, 823549.0, 823551.0, 1e6, 1e15, 0.7853981633974483, 2.356194490192345, 1.0000000000000002,
				0.9999999999999999));
		for (int i = 0; i < 200_000; i++) {
			double x = switch (i % 4) {
				case 0 -> r.nextDouble();
				case 1 -> r.nextDouble() * 200 - 100;
				case 2 -> Double.longBitsToDouble(r.nextLong()); // any bit pattern
				default -> Math.scalb(r.nextDouble(), r.nextInt(-1074, 1024));
			};
			special.add(x);
		}
		for (int i = 0; i < special.size(); i++) {
			double x = special.get(i);
			double y = special.get((i * 7 + 3) % special.size());
			long xb = Double.doubleToRawLongBits(x);
			long yb = Double.doubleToRawLongBits(y);
			o.out.println("log " + xb + " " + Double.doubleToRawLongBits(Math.log(x)));
			o.out.println("exp " + xb + " " + Double.doubleToRawLongBits(Math.exp(x)));
			o.out.println("sin " + xb + " " + Double.doubleToRawLongBits(Math.sin(x)));
			o.out.println("cos " + xb + " " + Double.doubleToRawLongBits(Math.cos(x)));
			o.out.println("pow " + xb + " " + yb + " " + Double.doubleToRawLongBits(Math.pow(x, y)));
			double small = (y % 16.0);
			long sb = Double.doubleToRawLongBits(small);
			o.out.println("pow " + xb + " " + sb + " " + Double.doubleToRawLongBits(Math.pow(x, small)));
		}
	}

	private interface Suite {
		void run(Oracle o);
	}

	private static void suite(Path dir, String name, Suite suite) throws IOException {
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve(name + ".txt")))) {
			suite.run(new Oracle(w));
		}
		System.out.println("oracle: wrote " + name);
	}

	void line(String key, long value) {
		out.println(key + " " + value);
	}

	void d(String key, double value) {
		line(key, Double.doubleToRawLongBits(value));
	}

	void f(String key, float value) {
		line(key, Float.floatToRawIntBits(value));
	}

	static final long[] SEEDS = {0L, 1L, -1L, 12345L, Long.MIN_VALUE, 0x5DEECE66DL, 8682522807148012L};
	static final int[] BOUNDS = {1, 2, 3, 7, 16, 100, 1 << 30, 1_000_000_007, Integer.MAX_VALUE};

	private void exercise(String p, RandomSource r) {
		for (int i = 0; i < 4; i++) line(p + "nextInt", r.nextInt());
		for (int b : BOUNDS) line(p + "nextInt(" + b + ")", r.nextInt(b));
		for (int i = 0; i < 3; i++) line(p + "nextLong", r.nextLong());
		for (int i = 0; i < 3; i++) f(p + "nextFloat", r.nextFloat());
		for (int i = 0; i < 3; i++) d(p + "nextDouble", r.nextDouble());
		for (int i = 0; i < 5; i++) d(p + "nextGaussian", r.nextGaussian());
		for (int i = 0; i < 3; i++) line(p + "nextBoolean", r.nextBoolean() ? 1 : 0);
		line(p + "between(-5,5)", r.nextIntBetweenInclusive(-5, 5));
		r.consumeCount(3);
		line(p + "afterConsume", r.nextInt());
		line(p + "fork", r.fork().nextLong());
		PositionalRandomFactory pos = r.forkPositional();
		line(p + "at(1,2,3)", pos.at(1, 2, 3).nextLong());
		line(p + "at(-30000000,-64,29999999)", pos.at(-30_000_000, -64, 29_999_999).nextLong());
		line(p + "fromHashOf", pos.fromHashOf("minecraft:test").nextLong());
		line(p + "fromSeed", pos.fromSeed(42L).nextLong());
	}

	static void random(Oracle o) {
		for (long seed : SEEDS) {
			o.exercise("legacy[" + seed + "].", new LegacyRandomSource(seed));
			o.exercise("xoroshiro[" + seed + "].", new XoroshiroRandomSource(seed));
			o.line("mix[" + seed + "]", RandomSupport.mixStafford13(seed));
		}
		int[][] positions = {{0, 0, 0}, {1, 2, 3}, {-1, -64, -1}, {30_000_000, 320, -30_000_000}, {123456, 7, -987654}};
		for (int[] p : positions) {
			o.line("getSeed(" + p[0] + "," + p[1] + "," + p[2] + ")", Mth.getSeed(p[0], p[1], p[2]));
		}
		for (String s : new String[] {"", "minecraft:overworld", "octave_-7", "ünïcödé"}) {
			RandomSupport.Seed128bit seed = RandomSupport.seedFromHashOf(s);
			o.line("md5lo[" + s + "]", seed.seedLo());
			o.line("md5hi[" + s + "]", seed.seedHi());
		}
	}
}
