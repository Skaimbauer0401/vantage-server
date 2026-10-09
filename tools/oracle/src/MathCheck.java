import java.util.SplittableRandom;

/**
 * Counts inputs where {@code Math} and {@code StrictMath} disagree. With
 * {@code -XX:+UnlockDiagnosticVMOptions -XX:-UseLibmIntrinsic} HotSpot should use
 * fdlibm for both, which is what mcrs relies on for bit-exact worldgen.
 */
public final class MathCheck {
	public static void main(String[] args) {
		SplittableRandom r = new SplittableRandom(1);
		int n = 2_000_000;
		int log = 0, exp = 0, pow = 0, sin = 0, cos = 0;
		double sink = 0;
		// Warm up so C2-compiled code is checked too, not just the interpreter.
		for (int round = 0; round < 3; round++) {
			log = exp = pow = sin = cos = 0;
			for (int i = 0; i < n; i++) {
				double x = r.nextDouble() * 1000.0;
				double y = r.nextDouble() * 40.0 - 20.0;
				double z = r.nextDouble() * 200.0 - 100.0;
				double a = Math.log(x), b = Math.exp(y), c = Math.pow(x, y / 10.0), d = Math.sin(z), e = Math.cos(z);
				if (a != StrictMath.log(x)) log++;
				if (b != StrictMath.exp(y)) exp++;
				if (c != StrictMath.pow(x, y / 10.0)) pow++;
				if (d != StrictMath.sin(z)) sin++;
				if (e != StrictMath.cos(z)) cos++;
				sink += a + b + c + d + e;
			}
		}
		System.out.printf("Math vs StrictMath differences in %d inputs: log %d, exp %d, pow %d, sin %d, cos %d (%s)%n",
				n, log, exp, pow, sin, cos, sink != 0 ? "ok" : "");
	}
}
