package dev.mcrs.bridge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Chunk loading through Rust (crates/mcrs-jvm/src/chunk_io.rs).
 *
 * <p>Rust reads and decompresses chunks in parallel and replaces each section's
 * palette with an {@code mcrs:palette} int array of global ids. The mixins on
 * {@code SerializableChunkData} then build containers from those ids with vanilla's
 * own {@link PalettedContainer#unpack}. Sections Rust couldn't resolve keep their
 * vanilla palette and are decoded by vanilla.
 *
 * <p>System properties: {@code mcrs.chunkio=false} disables all of this;
 * {@code mcrs.chunkio.verify=true} decodes every section both ways and logs mismatches.
 */
public final class ChunkIo {
	public static final String PALETTE_KEY = "mcrs:palette";
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.chunkio"));
	public static final boolean VERIFY = Boolean.getBoolean("mcrs.chunkio.verify");

	/** Rust {@code read_chunk} flag: keep the original palette for verification. */
	private static final int FLAG_KEEP_ORIGINAL = 1;

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
			Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8), task -> {
				Thread thread = new Thread(task, "mcrs-chunk-io-" + THREAD_ID.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			});

	/** Set while calling vanilla's loadAsync from our own fallback path. */
	public static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
	/** The factory of the {@code SerializableChunkData.parse} call running on this thread. */
	public static final ThreadLocal<PalettedContainerFactory> PARSE_FACTORY = new ThreadLocal<>();

	public static final AtomicLong VERIFIED = new AtomicLong();
	public static final AtomicLong MISMATCHES = new AtomicLong();

	private ChunkIo() {
	}

	public static boolean active() {
		return ENABLED && NativeBridge.isAvailable();
	}

	/** Sends the global block state and biome ids of {@code factory} to Rust. */
	public static void exportRegistry(PalettedContainerFactory factory) {
		if (!active()) {
			return;
		}
		StringBuilder blocks = new StringBuilder(1 << 20);
		IdMap<BlockState> blockIds = factory.blockStatesStrategy().globalMap();
		for (BlockState state : blockIds) {
			blocks.append(blockIds.getId(state)).append('\t').append(blockStateKey(state)).append('\n');
			// Palettes store a block's default state as just its name.
			if (state == state.getBlock().defaultBlockState() && !state.getProperties().isEmpty()) {
				blocks.append(blockIds.getId(state)).append('\t')
						.append(BuiltInRegistries.BLOCK.getKey(state.getBlock())).append('\n');
			}
		}
		StringBuilder biomes = new StringBuilder(1 << 12);
		IdMap<Holder<Biome>> biomeIds = factory.biomeStrategy().globalMap();
		for (Holder<Biome> biome : biomeIds) {
			biome.unwrapKey().ifPresent(key ->
					biomes.append(biomeIds.getId(biome)).append('\t').append(key.identifier()).append('\n'));
		}
		NativeBridge.setRegistry(SharedConstants.getCurrentVersion().dataVersion().version(),
				blocks.toString(), biomes.toString());
	}

	/** {@code name[k=v,...]} with properties sorted by name, matching palette.rs. */
	private static String blockStateKey(BlockState state) {
		StringBuilder key = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		List<Property<?>> properties = new ArrayList<>(state.getProperties());
		if (!properties.isEmpty()) {
			properties.sort(Comparator.comparing(Property::getName));
			key.append('[');
			for (int i = 0; i < properties.size(); i++) {
				if (i > 0) {
					key.append(',');
				}
				Property<?> property = properties.get(i);
				key.append(property.getName()).append('=').append(valueName(state, property));
			}
			key.append(']');
		}
		return key.toString();
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	/**
	 * Reads a chunk through Rust. Returns null when vanilla should read it instead.
	 */
	public static Optional<CompoundTag> read(MemorySegment regionDir, ChunkPos pos) {
		byte[] nbt = NativeBridge.readChunk(regionDir, pos.x(), pos.z(), VERIFY ? FLAG_KEEP_ORIGINAL : 0);
		if (nbt == null) {
			return null;
		}
		if (nbt.length == 0) {
			return Optional.empty();
		}
		try {
			return Optional.of(NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt))));
		} catch (IOException e) {
			return null;
		}
	}

	/** Packed data from {@code mcrs:palette} ids, or null if the section has none. */
	public static <T> PalettedContainerRO.PackedData<T> packedData(Strategy<T> strategy, CompoundTag tag) {
		Optional<int[]> ids = tag.getIntArray(PALETTE_KEY);
		if (ids.isEmpty()) {
			return null;
		}
		IdMap<T> globalMap = strategy.globalMap();
		List<T> palette = new ArrayList<>(ids.get().length);
		for (int id : ids.get()) {
			T value = globalMap.byId(id);
			if (value == null) {
				throw new IllegalStateException("mcrs: unknown global palette id " + id + "; registry out of sync");
			}
			palette.add(value);
		}
		return new PalettedContainerRO.PackedData<>(palette, tag.getLongArray("data").map(LongStream::of));
	}

	/** Verification: compares a container built by vanilla with one built from Rust's ids. */
	public static <T> void verify(PalettedContainerRO<T> vanilla, PalettedContainerRO<T> fast, int size, String what,
			ChunkPos pos, int sectionY) {
		VERIFIED.incrementAndGet();
		for (int i = 0; i < size; i++) {
			int x = i & (size == 4096 ? 15 : 3);
			int z = (i >> (size == 4096 ? 4 : 2)) & (size == 4096 ? 15 : 3);
			int y = i >> (size == 4096 ? 8 : 4);
			if (vanilla.get(x, y, z) != fast.get(x, y, z)) {
				if (MISMATCHES.incrementAndGet() <= 20) {
					McrsBridge.LOGGER.error("mcrs verify: {} mismatch in chunk {} section {} at {} {} {}: vanilla {} vs mcrs {}",
							what, pos, sectionY, x, y, z, vanilla.get(x, y, z), fast.get(x, y, z));
				}
				return;
			}
		}
	}
}
