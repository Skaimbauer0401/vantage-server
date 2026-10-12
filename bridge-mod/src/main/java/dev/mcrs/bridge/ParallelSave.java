package dev.mcrs.bridge;

import com.google.common.collect.MapMaker;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Chunk saves serialize and compress on the worker that builds the chunk's NBT, in parallel,
 * instead of on vanilla's single chunk IO thread, which then only writes the finished bytes
 * into the region file. The bytes are exactly what vanilla's {@code RegionFile.ChunkBuffer}
 * produces (same streams, same compression), so files are identical. When many chunks are
 * saved per second (pregeneration) that IO thread was the bottleneck of generation.
 *
 * <p>{@code -Dmcrs.parallelSave=false} restores vanilla; {@code -Dmcrs.parallelSave.verify=true}
 * also encodes every chunk the vanilla way on the IO thread and compares.
 */
public final class ParallelSave {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.parallelSave")) && !McrsMixinPlugin.otherChunkSystem();
	public static final boolean VERIFY = Boolean.getBoolean("mcrs.parallelSave.verify");
	public static final AtomicLong USED = new AtomicLong();
	public static final AtomicLong VERIFIED = new AtomicLong();
	public static final AtomicLong MISMATCHED = new AtomicLong();

	/** Encoded chunks by tag identity; weak keys, so superseded writes don't stay. */
	private static final ConcurrentMap<CompoundTag, Encoded> ENCODED = new MapMaker().weakKeys().makeMap();

	public record Encoded(RegionFileVersion version, byte[] bytes, int length) {
	}

	private ParallelSave() {
	}

	/** Encodes {@code tag} for a region file write; called where the tag is built. */
	public static void encode(CompoundTag tag) {
		if (!ENABLED || tag == null) {
			return;
		}
		try {
			ENCODED.put(tag, encode(tag, RegionFileVersion.getSelected()));
		} catch (IOException e) {
			// The IO thread encodes it then, as in vanilla.
		}
	}

	/** The bytes of {@code RegionFile.ChunkBuffer} after closing: length, version id, compressed NBT. */
	public static Encoded encode(CompoundTag tag, RegionFileVersion version) throws IOException {
		Buffer buffer = new Buffer(version);
		try (DataOutputStream output = new DataOutputStream(version.wrap(buffer))) {
			NbtIo.write(tag, output);
		}
		return new Encoded(version, buffer.result, buffer.length);
	}

	/** The encoding of exactly this tag for this region file version, if made; removes it. */
	public static Encoded take(CompoundTag tag, RegionFileVersion version) {
		Encoded encoded = ENCODED.remove(tag);
		if (encoded == null || encoded.version() != version) {
			return null;
		}
		if (VERIFY) {
			try {
				Encoded vanilla = encode(tag, version);
				if (vanilla.length() == encoded.length() && Arrays.equals(vanilla.bytes(), 0, vanilla.length(), encoded.bytes(), 0, encoded.length())) {
					VERIFIED.incrementAndGet();
				} else {
					MISMATCHED.incrementAndGet();
				}
			} catch (IOException e) {
				MISMATCHED.incrementAndGet();
			}
		}
		USED.incrementAndGet();
		return encoded;
	}

	public static ByteBuffer buffer(Encoded encoded) {
		return ByteBuffer.wrap(encoded.bytes(), 0, encoded.length());
	}

	/** {@code RegionFile.ChunkBuffer} without the region file. */
	private static final class Buffer extends ByteArrayOutputStream {
		byte[] result;
		int length;

		Buffer(RegionFileVersion version) {
			super(8096);
			super.write(0);
			super.write(0);
			super.write(0);
			super.write(0);
			super.write(version.getId());
		}

		@Override
		public void close() {
			ByteBuffer.wrap(this.buf, 0, this.count).putInt(0, this.count - 5 + 1);
			result = this.buf;
			length = this.count;
		}
	}
}
