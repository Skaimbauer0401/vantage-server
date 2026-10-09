package dev.mcrs.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Java side of the Rust bridge. mcrs passes the address of its {@code BridgeTable}
 * (crates/mcrs-jvm/src/bridge.rs) as {@code -Dmcrs.bridge}; each function pointer
 * is bound as a Panama FFM downcall. Keep the layout in sync with the Rust struct.
 */
public final class NativeBridge {
	public static final int ABI_VERSION = 2;

	private static final String MAGIC = "MCRSBRDG";
	private static final long HEADER_SIZE = 16;
	private static final int FN_COUNT = 5;

	public static final int TRACE = 0, DEBUG = 1, INFO = 2, WARN = 3, ERROR = 4;

	private static MethodHandle log;
	private static MethodHandle onTick;
	private static MethodHandle setRegistry;
	private static MethodHandle readChunk;
	private static MethodHandle freeBuffer;

	private NativeBridge() {
	}

	/** Binds the bridge. Returns false when not running inside mcrs. */
	static boolean init() {
		String property = System.getProperty("mcrs.bridge");
		if (property == null) {
			return false;
		}

		MemorySegment header = MemorySegment.ofAddress(Long.parseUnsignedLong(property)).reinterpret(HEADER_SIZE);
		String magic = new String(header.asSlice(0, 8).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.US_ASCII);
		int abi = header.get(ValueLayout.JAVA_INT, 8);
		int count = header.get(ValueLayout.JAVA_INT, 12);
		if (!MAGIC.equals(magic) || abi != ABI_VERSION || count < FN_COUNT) {
			throw new IllegalStateException("mcrs bridge mismatch: magic=" + magic + " abi=" + abi
					+ " (expected " + ABI_VERSION + ") functions=" + count + ". Rebuild mcrs and the bridge mod together.");
		}

		MemorySegment table = header.reinterpret(HEADER_SIZE + ValueLayout.ADDRESS.byteSize() * count);
		Linker linker = Linker.nativeLinker();
		log = linker.downcallHandle(fn(table, 0),
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		// Called every tick: critical skips the thread state transition.
		onTick = linker.downcallHandle(fn(table, 1),
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG), Linker.Option.critical(false));
		setRegistry = linker.downcallHandle(fn(table, 2), FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		readChunk = linker.downcallHandle(fn(table, 3), FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		freeBuffer = linker.downcallHandle(fn(table, 4),
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), Linker.Option.critical(false));
		return true;
	}

	private static MemorySegment fn(MemorySegment table, int index) {
		return table.get(ValueLayout.ADDRESS, HEADER_SIZE + ValueLayout.ADDRESS.byteSize() * index);
	}

	public static boolean isAvailable() {
		return log != null;
	}

	public static void log(int level, String message) {
		byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment utf8 = arena.allocate(Math.max(1, bytes.length));
			MemorySegment.copy(bytes, 0, utf8, ValueLayout.JAVA_BYTE, 0, bytes.length);
			log.invokeExact(level, utf8, (long) bytes.length);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static void onTick(long nanos) {
		try {
			onTick.invokeExact(nanos);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Tables are {@code <id>	<key>
} lines; see crates/mcrs-chunk/src/palette.rs. */
	public static void setRegistry(int dataVersion, String blockStates, String biomes) {
		byte[] blocks = blockStates.getBytes(StandardCharsets.UTF_8);
		byte[] biomeBytes = biomes.getBytes(StandardCharsets.UTF_8);
		try (Arena arena = Arena.ofConfined()) {
			setRegistry.invokeExact(dataVersion, arena.allocateFrom(ValueLayout.JAVA_BYTE, blocks), (long) blocks.length,
					arena.allocateFrom(ValueLayout.JAVA_BYTE, biomeBytes), (long) biomeBytes.length);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Reads one chunk through Rust. Returns its uncompressed NBT, an empty array if the
	 * chunk doesn't exist, or null if Rust couldn't read it (caller falls back to vanilla).
	 */
	public static byte[] readChunk(MemorySegment regionDir, int chunkX, int chunkZ, int flags) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
			int result = (int) readChunk.invokeExact(regionDir, regionDir.byteSize(), chunkX, chunkZ, flags, out, outLen);
			if (result < 0) {
				return null;
			}
			if (result == 0) {
				return EMPTY;
			}
			long length = outLen.get(ValueLayout.JAVA_LONG, 0);
			MemorySegment data = out.get(ValueLayout.ADDRESS, 0).reinterpret(length);
			try {
				return data.toArray(ValueLayout.JAVA_BYTE);
			} finally {
				freeBuffer.invokeExact(data, length);
			}
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	private static final byte[] EMPTY = new byte[0];
}
