package dev.mcrs.bridge;

/**
 * zlib compression through Rust's libdeflate, like Krypton's native compression: about 3.5x
 * the speed of java.util.zip.Deflater at the same level (6) and ratio. The data is ordinary
 * zlib, so clients and region readers decompress it exactly as before.
 * <ul>
 * <li>Network packets (all editions): only the transport encoding changes. Off with
 * {@code -Dmcrs.nativeCompression=false}.</li>
 * <li>Region files (performance edition, {@code -Dmcrs.fastRegionCompression}): the stored
 * chunks are the same, but the files are no longer byte-identical to vanilla's.</li>
 * </ul>
 */
public final class NativeCompression {
	public static final boolean NETWORK = !"false".equals(System.getProperty("mcrs.nativeCompression"));
	public static final boolean REGION = Edition.flag("mcrs.fastRegionCompression", Edition.PERFORMANCE);
	/** java.util.zip.Deflater.DEFAULT_COMPRESSION is level 6. */
	public static final int LEVEL = 6;

	private static final ThreadLocal<byte[]> OUT = ThreadLocal.withInitial(() -> new byte[1 << 16]);

	private NativeCompression() {
	}

	public static boolean available() {
		return NativeBridge.isAvailable();
	}

	/**
	 * Compresses sample data (random, repetitive, and real NBT-like) with libdeflate and
	 * inflates it with java.util.zip like a client does; returns a summary.
	 */
	public static String selfTest() {
		java.util.Random random = new java.util.Random(1);
		int ok = 0;
		int bad = 0;
		long in = 0;
		long outBytes = 0;
		for (int i = 0; i < 2000; i++) {
			int length = 1 + random.nextInt(i % 10 == 0 ? 2_000_000 : 40_000);
			byte[] data = new byte[length];
			switch (i % 3) {
				case 0 -> random.nextBytes(data);
				case 1 -> {
					for (int k = 0; k < length; k++) {
						data[k] = (byte) (k % 37 < 30 ? 0 : random.nextInt(4));
					}
				}
				default -> {
					for (int k = 0; k < length; k++) {
						data[k] = (byte) ("minecraft:stone minecraft:dirt Palette BlockStates ".charAt(k % 51) + (random.nextInt(50) == 0 ? 1 : 0));
					}
				}
			}
			byte[] compressed = new byte[NativeBridge.zlibBound(length)];
			int written = NativeBridge.zlibCompress(LEVEL, data, 0, length, compressed, 0);
			try {
				java.util.zip.Inflater inflater = new java.util.zip.Inflater();
				inflater.setInput(compressed, 0, written);
				byte[] back = new byte[length];
				int n = inflater.inflate(back);
				boolean same = n == length && inflater.finished() && java.util.Arrays.equals(back, data);
				inflater.end();
				if (same) {
					ok++;
				} else {
					bad++;
				}
			} catch (java.util.zip.DataFormatException e) {
				bad++;
			}
			in += length;
			outBytes += written;
		}
		return String.format(java.util.Locale.ROOT, "native compression self-test: %d round trips identical, %d differ (%.1f MB, ratio %.3f)", ok, bad, in / 1e6,
				(double) outBytes / in);
	}

	/** A reusable per-thread buffer of at least {@code size} bytes. */
	public static byte[] buffer(int size) {
		byte[] out = OUT.get();
		if (out.length < size) {
			out = new byte[Integer.highestOneBit(size - 1) << 1];
			OUT.set(out);
		}
		return out;
	}
}
