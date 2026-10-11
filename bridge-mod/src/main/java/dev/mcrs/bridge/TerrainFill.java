package dev.mcrs.bridge;

import dev.mcrs.bridge.mixin.LevelChunkSectionAccessor;
import dev.mcrs.bridge.mixin.NoiseChunkAccessor;
import dev.mcrs.bridge.mixin.PalettedContainerAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * {@code NoiseBasedChunkGenerator.doFill} in Rust.
 *
 * <p>Rust returns the filled sections, heightmap tops, fluid post-processing positions,
 * and its final density-cache and aquifer state. That state is copied into this
 * chunk's {@link NoiseChunk}, so the surface and carver steps that follow in Java see
 * exactly what they would have seen after vanilla's {@code doFill}.
 *
 * <p>Chunks near structures with terrain adaptation (villages, ancient cities...) or
 * next to old-version chunks (blending) stay on vanilla for now.
 */
public final class TerrainFill {
	public static final boolean VERIFY = Boolean.getBoolean("mcrs.worldgen.verify");

	public static final AtomicLong FILLED = new AtomicLong();
	public static final AtomicLong SKIPPED = new AtomicLong();
	public static final AtomicLong VERIFIED = new AtomicLong();
	public static final AtomicLong MISMATCHED = new AtomicLong();

	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	/** Generator handle per RandomState; 0 = unsupported (vanilla). */
	private static final Map<RandomState, Long> HANDLES = new IdentityHashMap<>();
	/** RandomStates whose surface step runs in Rust too. */
	private static final java.util.Set<RandomState> SURFACE_IN_RUST = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	/** BiomeManager of each chunk being built (captured in buildTerrain, used by doFill). */
	public static final Map<ChunkAccess, net.minecraft.world.level.biome.BiomeManager> BIOME_MANAGERS =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** Chunks whose surface Rust already built; buildSurface skips them. */
	public static final java.util.Set<ChunkAccess> SURFACE_DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private TerrainFill() {
	}

	static synchronized long handle(RandomState randomState, NoiseGeneratorSettings settings, ChunkAccess chunk,
			net.minecraft.world.level.chunk.ChunkGenerator generator) {
		Long handle = HANDLES.get(randomState);
		if (handle == null) {
			var json = Worldgen.export(randomState, settings, biomeIds(chunk), generator.getBiomeSource());
			handle = json == null ? 0L : NativeBridge.wgLoad(json.toString().getBytes(StandardCharsets.UTF_8));
			HANDLES.put(randomState, handle);
			if (handle != 0 && json.has("surface")) {
				SURFACE_IN_RUST.add(randomState);
			}
		}
		return handle;
	}

	@SuppressWarnings("unchecked")
	static net.minecraft.core.IdMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeIds(ChunkAccess chunk) {
		Object biomes = chunk.getSection(0).getBiomes();
		return ((PalettedContainerAccessor<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>) biomes).mcrs$strategy().globalMap();
	}

	/** Whether Rust builds the surface for chunks of this RandomState. */
	public static synchronized boolean surfaceInRust(NoiseChunk noiseChunk) {
		return SURFACE_IN_RUST.contains(((NoiseChunkAccessor) noiseChunk).mcrs$randomState());
	}

	/** Like {@link #surfaceInRust(NoiseChunk)}, loading the dimension first if needed. */
	public static boolean surfaceInRust(NoiseChunk noiseChunk, ChunkAccess chunk, NoiseGeneratorSettings settings,
			net.minecraft.world.level.chunk.ChunkGenerator generator) {
		if (Worldgen.ENABLED && NativeBridge.isAvailable()) {
			handle(((NoiseChunkAccessor) noiseChunk).mcrs$randomState(), settings, chunk, generator);
		}
		return surfaceInRust(noiseChunk);
	}

	/** Returns true if Rust filled the chunk; false means vanilla's doFill must run. */
	public static boolean tryFill(NoiseChunk noiseChunk, ChunkAccess chunk, NoiseGeneratorSettings settings,
			net.minecraft.world.level.chunk.ChunkGenerator generator) {
		Result result = compute(noiseChunk, chunk, settings, generator);
		if (result == null) {
			SKIPPED.incrementAndGet();
			return false;
		}
		try {
			result.applyBlocks(chunk);
			result.applyHeightmapsAndPostProcessing(chunk);
			result.applyState(noiseChunk);
			if (result.surfaceDone) {
				SURFACE_DONE.add(chunk);
				if (result.carved) {
					Carvers.DONE.add(chunk);
					Carvers.CARVED.incrementAndGet();
				}
			}
		} finally {
			result.close();
		}
		FILLED.incrementAndGet();
		return true;
	}

	/** Verify mode: vanilla already filled the chunk; compare Rust's result with it. */
	public static void verify(NoiseChunk noiseChunk, ChunkAccess chunk, NoiseGeneratorSettings settings,
			net.minecraft.world.level.chunk.ChunkGenerator generator) {
		Result result = compute(noiseChunk, chunk, settings, generator);
		if (result == null) {
			return;
		}
		try {
			List<String> problems = result.compare(chunk, noiseChunk);
			VERIFIED.incrementAndGet();
			if (!problems.isEmpty() && MISMATCHED.incrementAndGet() <= 20) {
				McrsBridge.LOGGER.error("mcrs verify: terrain of chunk {} differs: {}", chunk.getPos(),
						problems.subList(0, Math.min(8, problems.size())));
			}
		} finally {
			result.close();
		}
	}

	private static Result compute(NoiseChunk noiseChunk, ChunkAccess chunk, NoiseGeneratorSettings settings,
			net.minecraft.world.level.chunk.ChunkGenerator generator) {
		if (!Worldgen.ENABLED || !NativeBridge.isAvailable()) {
			return null;
		}
		RandomState randomState = ((NoiseChunkAccessor) noiseChunk).mcrs$randomState();
		long handle = handle(randomState, settings, chunk, generator);
		if (handle == 0) {
			return null;
		}
		SamplerContext context = Reflect.context(noiseChunk);
		Beardifier beardifier = context.getField(Beardifier.CONTEXT_KEY);
		int[] beard = beardifier == null ? new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0} : Reflect.beard(beardifier);
		if (context.getField(Blender.CONTEXT_KEY) != null) {
			return null;
		}
		DensityVolume volume = noiseChunk.volume();
		if (!VERIFY) {
			for (int y = volume.minBlockY(); y <= volume.maxBlockY(); y += 16) {
				if (!chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()) {
					return null;
				}
			}
		}
		ChunkPos pos = chunk.getPos();
		int[] params = params(chunk, settings, generator, surfaceInRust(noiseChunk));
		// Carving: with the surface in Rust, Rust also applies vanilla's carving mask.
		boolean carve = Carvers.ENABLED && params[2] == 1 && generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
		if (carve) {
			var region = (net.minecraft.server.level.WorldGenRegion) Reflect.get(BIOME_MANAGERS.get(chunk), "noiseBiomeSource");
			int[] mask = Carvers.maskInts((net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator) generator, chunk, randomState, region);
			int[] joined = java.util.Arrays.copyOf(params, params.length + mask.length);
			System.arraycopy(mask, 0, joined, params.length, mask.length);
			params = joined;
		}
		MemorySegment buffer = NativeBridge.wgFill(handle, pos.x(), pos.z(), volume.minBlockY(), volume.sizeY(), beard, params);
		if (buffer == null) {
			return null;
		}
		Result result = new Result(buffer, volume);
		result.carved = carve;
		return result;
	}

	/** See wg_fill in crates/mcrs-jvm/src/worldgen.rs. */
	private static int[] params(ChunkAccess chunk, NoiseGeneratorSettings settings, net.minecraft.world.level.chunk.ChunkGenerator generator,
			boolean surface) {
		var heights = chunk.getHeightAccessorForGeneration();
		var context = new net.minecraft.world.level.levelgen.WorldGenerationContext(generator, heights);
		var biomeManager = BIOME_MANAGERS.get(chunk);
		boolean doSurface = surface && biomeManager != null;
		int minQuartY = chunk.getMinY() >> 2;
		int quartHeight = chunk.getHeight() >> 2;
		int[] params = new int[12 + (doSurface ? 9 * quartHeight * 16 : 0)];
		params[0] = chunk.getMinY();
		params[1] = chunk.getHeight();
		params[2] = doSurface ? 1 : 0;
		params[3] = heights.getMinY();
		params[4] = heights.getMaxY();
		params[5] = context.getMinGenY();
		params[6] = context.getGenDepth();
		params[7] = context.seaLevel();
		params[8] = Block.getId(settings.defaultBlock());
		params[9] = Block.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		params[10] = minQuartY;
		params[11] = quartHeight;
		if (doSurface) {
			var region = (net.minecraft.server.level.WorldGenRegion) Reflect.get(biomeManager, "noiseBiomeSource");
			var ids = biomeIds(chunk);
			ChunkPos pos = chunk.getPos();
			int i = 12;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					ChunkAccess c = region.getChunk(pos.x() + dx, pos.z() + dz);
					if (fastBiomes(c, chunk, quartHeight)) {
						i = copyBiomeIds(c, ids, quartHeight, params, i);
						continue;
					}
					int qx0 = (pos.x() + dx) << 2;
					int qz0 = (pos.z() + dz) << 2;
					for (int qy = 0; qy < quartHeight; qy++) {
						for (int qz = 0; qz < 4; qz++) {
							for (int qx = 0; qx < 4; qx++) {
								params[i++] = ids.getId(c.getNoiseBiome(qx0 + qx, minQuartY + qy, qz0 + qz));
							}
						}
					}
				}
			}
		}
		return params;
	}

	/**
	 * Whether {@link #copyBiomeIds} gives what {@code getNoiseBiome} would for every quart of
	 * {@code c}: biomes exist (else vanilla throws) and the height range matches the center.
	 */
	private static boolean fastBiomes(ChunkAccess c, ChunkAccess center, int quartHeight) {
		return (!(c instanceof ProtoChunk proto) || proto.getHighestGeneratedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES))
				&& c.getMinY() == center.getMinY() && c.getHeight() == center.getHeight() && (quartHeight & 3) == 0
				&& c.getSections().length == quartHeight >> 2;
	}

	/** Biome ids of {@code c} in quart y, z, x order, read straight from the section containers. */
	private static int copyBiomeIds(ChunkAccess c, net.minecraft.core.IdMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> ids,
			int quartHeight, int[] params, int i) {
		net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> last = null;
		int lastId = 0;
		LevelChunkSection[] sections = c.getSections();
		for (LevelChunkSection section : sections) {
			var biomes = section.getBiomes();
			for (int y = 0; y < 4; y++) {
				for (int z = 0; z < 4; z++) {
					for (int x = 0; x < 4; x++) {
						var biome = biomes.get(x, y, z);
						if (biome != last) {
							last = biome;
							lastId = ids.getId(biome);
						}
						params[i++] = lastId;
					}
				}
			}
		}
		return i;
	}

	/** A parsed Rust fill result. */
	private static final class Result implements AutoCloseable {
		private final MemorySegment buffer;
		private final DensityVolume volume;
		private long offset;

		final boolean surfaceDone;
		/** Rust applied the carving mask too. */
		boolean carved;
		private final List<int[]> sectionPalettes = new ArrayList<>();
		private final List<int[]> sectionCounts = new ArrayList<>();
		private final List<long[]> sectionData = new ArrayList<>();
		private final int[] oceanY = new int[256];
		private final int[] oceanState = new int[256];
		private final int[] surfaceY = new int[256];
		private final int[] surfaceState = new int[256];
		private int[] post;
		private final List<CellState> cells = new ArrayList<>();
		private AquiferState aquifer;

		Result(MemorySegment buffer, DensityVolume volume) {
			this.buffer = buffer;
			this.volume = volume;
			this.surfaceDone = i() == 1;
			int sections = i();
			for (int s = 0; s < sections; s++) {
				int[] palette = ints(i());
				sectionPalettes.add(palette);
				if (palette.length > 0) {
					sectionCounts.add(ints(4));
					long[] data = new long[i()];
					MemorySegment.copy(buffer, LONG, offset, data, 0, data.length);
					offset += 8L * data.length;
					sectionData.add(data);
				} else {
					sectionCounts.add(null);
					sectionData.add(null);
				}
			}
			readInto(oceanY);
			readInto(oceanState);
			readInto(surfaceY);
			readInto(surfaceState);
			post = ints(i() * 3);
			int cellCount = i();
			for (int c = 0; c < cellCount; c++) {
				CellState cell = new CellState();
				if (i() == 1) {
					int[] v = ints(9);
					cell.volume = new DensityVolume(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
					cell.buffer = new float[i()];
					MemorySegment.copy(buffer, FLOAT, offset, cell.buffer, 0, cell.buffer.length);
					offset += 4L * cell.buffer.length;
				}
				cell.valueKey = l();
				cell.value = f();
				cells.add(cell);
			}
			if (i() == 1) {
				aquifer = new AquiferState();
				int n = i();
				aquifer.status = new Aquifer.FluidStatus[n];
				for (int k = 0; k < n; k++) {
					int has = i();
					int level = i();
					int fluid = i();
					aquifer.status[k] = has == 1 ? new Aquifer.FluidStatus(level, Block.stateById(fluid)) : null;
				}
				n = i();
				aquifer.location = new long[n];
				for (int k = 0; k < n; k++) {
					int has = i();
					int x = i();
					int y = i();
					int z = i();
					aquifer.location[k] = has == 1 ? BlockPos.asLong(x, y, z) : Long.MAX_VALUE;
				}
				n = i();
				aquifer.surfaceKeys = new long[n];
				aquifer.surfaceValues = new int[n];
				for (int k = 0; k < n; k++) {
					aquifer.surfaceKeys[k] = ChunkPos.pack(i(), i());
					aquifer.surfaceValues[k] = i();
				}
				aquifer.shouldSchedule = i() == 1;
			}
		}

		private int i() {
			int v = buffer.get(INT, offset);
			offset += 4;
			return v;
		}

		private long l() {
			long v = buffer.get(LONG, offset);
			offset += 8;
			return v;
		}

		private float f() {
			float v = buffer.get(FLOAT, offset);
			offset += 4;
			return v;
		}

		private int[] ints(int n) {
			int[] values = new int[n];
			readInto(values);
			return values;
		}

		private void readInto(int[] values) {
			MemorySegment.copy(buffer, INT, offset, values, 0, values.length);
			offset += 4L * values.length;
		}

		@Override
		public void close() {
			NativeBridge.free(buffer);
		}

		private LevelChunkSection section(ChunkAccess chunk, int index) {
			return chunk.getSection(index);
		}

		void applyBlocks(ChunkAccess chunk) {
			for (int s = 0; s < sectionPalettes.size(); s++) {
				int[] ids = sectionPalettes.get(s);
				if (ids.length == 0) {
					continue;
				}
				LevelChunkSection section = section(chunk, s);
				@SuppressWarnings("unchecked")
				Strategy<BlockState> strategy = ((PalettedContainerAccessor<BlockState>) (Object) section.getStates()).mcrs$strategy();
				List<BlockState> palette = new ArrayList<>(ids.length);
				for (int id : ids) {
					palette.add(Block.stateById(id));
				}
				PalettedContainer<BlockState> states = PalettedContainer.unpack(strategy,
						new PalettedContainerRO.PackedData<>(palette, Optional.of(LongStream.of(sectionData.get(s))))).getOrThrow();
				// buildTerrain releases every section after doFill; keep the lock balanced.
				states.acquire();
				LevelChunkSectionAccessor access = (LevelChunkSectionAccessor) section;
				access.mcrs$setStates(states);
				int[] counts = sectionCounts.get(s);
				access.mcrs$setNonEmptyBlockCount((short) counts[0]);
				access.mcrs$setFluidCount((short) counts[1]);
				access.mcrs$setTickingBlockCount((short) counts[2]);
				access.mcrs$setTickingFluidCount((short) counts[3]);
			}
		}

		void applyHeightmapsAndPostProcessing(ChunkAccess chunk) {
			Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
			Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
			for (int column = 0; column < 256; column++) {
				int x = column & 15;
				int z = column >> 4;
				if (oceanY[column] != Integer.MIN_VALUE) {
					oceanFloor.update(x, oceanY[column], z, Block.stateById(oceanState[column]));
				}
				if (surfaceY[column] != Integer.MIN_VALUE) {
					worldSurface.update(x, surfaceY[column], z, Block.stateById(surfaceState[column]));
				}
			}
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int k = 0; k < post.length; k += 3) {
				chunk.markPosForPostProcessing(pos.set(post[k], post[k + 1], post[k + 2]));
			}
		}

		/** Copies Rust's density-cache and aquifer state into the chunk's NoiseChunk. */
		void applyState(NoiseChunk noiseChunk) {
			SamplerContext context = Reflect.context(noiseChunk);
			// After carving the noise chunk is closed, so its density caches are no longer used:
			// they only differ in which single block topMaterial sampled last.
			for (int id = 0; id < (carved ? 0 : cells.size()); id++) {
				CellState rust = cells.get(id);
				Object cell = Reflect.cacheCell(context, id);
				ScopedDensityBuffer old = Reflect.cellBuffer(cell);
				if (old != null) {
					old.close();
				}
				if (rust.volume != null) {
					ScopedDensityBuffer b = context.acquireBuffer(rust.volume);
					for (int k = 0; k < rust.buffer.length; k++) {
						b.set(k, rust.buffer[k]);
					}
					Reflect.setCell(cell, rust.volume, b, rust.valueKey, rust.value);
				} else {
					Reflect.setCell(cell, null, null, rust.valueKey, rust.value);
				}
			}
			if (aquifer != null) {
				Object java = noiseChunk.aquifer();
				Aquifer.FluidStatus[] status = Reflect.get(java, "aquiferCache");
				long[] location = Reflect.get(java, "aquiferLocationCache");
				if (status.length != aquifer.status.length || location.length != aquifer.location.length) {
					throw new IllegalStateException("mcrs: aquifer grid size differs from Rust's");
				}
				System.arraycopy(aquifer.status, 0, status, 0, status.length);
				System.arraycopy(aquifer.location, 0, location, 0, location.length);
				Long2IntMap surface = Reflect.get(java, "surfaceLevelCache");
				surface.clear();
				for (int k = 0; k < aquifer.surfaceKeys.length; k++) {
					surface.put(aquifer.surfaceKeys[k], aquifer.surfaceValues[k]);
				}
				Reflect.set(java, "shouldScheduleFluidUpdate", aquifer.shouldSchedule);
			}
		}

		/** Differences between vanilla's filled chunk and Rust's result (verify mode). */
		List<String> compare(ChunkAccess chunk, NoiseChunk noiseChunk) {
			List<String> problems = new ArrayList<>();
			for (int s = 0; s < sectionPalettes.size(); s++) {
				LevelChunkSection section = section(chunk, s);
				int[] ids = sectionPalettes.get(s);
				long[] data = sectionData.get(s);
				int bits = ids.length == 0 ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(ids.length - 1));
				int perLong = bits == 0 ? 1 : 64 / bits;
				int blockDiffs = 0;
				for (int index = 0; index < 4096; index++) {
					BlockState expected = section.getStates().get(index & 15, index >> 8, (index >> 4) & 15);
					BlockState actual;
					if (ids.length == 0) {
						actual = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
					} else {
						int paletteIndex = (int) ((data[index / perLong] >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
						actual = Block.stateById(ids[paletteIndex]);
					}
					if (expected != actual && blockDiffs++ == 0) {
						problems.add("section " + s + " index " + index + ": vanilla " + expected + " rust " + actual);
					}
				}
				if (blockDiffs > 1) {
					problems.add("section " + s + ": " + blockDiffs + " blocks differ");
				}
				if (ids.length > 0) {
					int[] counts = sectionCounts.get(s);
					LevelChunkSection fresh = new LevelChunkSection(section.getStates().copy(), section.getBiomes());
					fresh.recalcBlockCounts();
					// Counts are private; compare through what they drive.
					if (fresh.hasOnlyAir() != (counts[0] == 0) || fresh.isRandomlyTicking() != (counts[2] > 0 || counts[3] > 0)) {
						problems.add("section " + s + " counts differ: " + Arrays.toString(counts));
					}
				}
			}
			for (Heightmap.Types type : new Heightmap.Types[] {Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG}) {
				Heightmap map = chunk.getOrCreateHeightmapUnprimed(type);
				int[] rustY = type == Heightmap.Types.OCEAN_FLOOR_WG ? oceanY : surfaceY;
				for (int column = 0; column < 256; column++) {
					int expected = map.getFirstAvailable(column & 15, column >> 4);
					int actual = rustY[column] == Integer.MIN_VALUE ? chunk.getMinY() : rustY[column] + 1;
					if (expected != actual) {
						problems.add(type + " column " + column + ": vanilla " + expected + " rust " + actual);
						break;
					}
				}
			}
			ShortList[] postProcessing = chunk.getPostProcessing();
			List<List<Short>> rustPost = new ArrayList<>();
			for (int s = 0; s < postProcessing.length; s++) {
				rustPost.add(new ArrayList<>());
			}
			for (int k = 0; k < post.length; k += 3) {
				BlockPos p = new BlockPos(post[k], post[k + 1], post[k + 2]);
				rustPost.get(chunk.getSectionIndex(p.getY())).add(ProtoChunk.packOffsetCoordinates(p));
			}
			for (int s = 0; s < postProcessing.length; s++) {
				List<Short> expected = postProcessing[s] == null ? List.of() : new ArrayList<>(postProcessing[s]);
				if (!expected.equals(rustPost.get(s))) {
					problems.add("post-processing in section " + s + ": vanilla " + expected.size() + " rust " + rustPost.get(s).size());
				}
			}
			SamplerContext context = Reflect.context(noiseChunk);
			// After carving the noise chunk is closed, so its density caches are no longer used:
			// they only differ in which single block topMaterial sampled last.
			for (int id = 0; id < (carved ? 0 : cells.size()); id++) {
				CellState rust = cells.get(id);
				Object cell = Reflect.cacheCell(context, id);
				DensityVolume javaVolume = Reflect.get(cell, "volume");
				ScopedDensityBuffer javaBuffer = Reflect.cellBuffer(cell);
				long key = Reflect.get(cell, "valueKey");
				float value = Reflect.get(cell, "value");
				if (!java.util.Objects.equals(javaVolume, rust.volume)) {
					problems.add("cache " + id + " volume: vanilla " + javaVolume + " rust " + rust.volume);
					continue;
				}
				if (rust.volume != null) {
					for (int k = 0; k < rust.buffer.length; k++) {
						if (Float.floatToRawIntBits(javaBuffer.get(k)) != Float.floatToRawIntBits(rust.buffer[k])) {
							problems.add("cache " + id + " value " + k + ": vanilla " + javaBuffer.get(k) + " rust " + rust.buffer[k]);
							break;
						}
					}
				}
				if (key != rust.valueKey || Float.floatToRawIntBits(value) != Float.floatToRawIntBits(rust.value)) {
					problems.add("cache " + id + " last value: vanilla " + BlockPos.of(key) + "=" + value + " rust "
							+ BlockPos.of(rust.valueKey) + "=" + rust.value);
				}
			}
			if (aquifer != null) {
				Object java = noiseChunk.aquifer();
				Aquifer.FluidStatus[] status = Reflect.get(java, "aquiferCache");
				long[] location = Reflect.get(java, "aquiferLocationCache");
				if (!Arrays.equals(location, aquifer.location)) {
					problems.add("aquifer locations differ");
				}
				if (!Arrays.equals(status, aquifer.status)) {
					problems.add("aquifer statuses differ");
				}
				Long2IntMap surface = Reflect.get(java, "surfaceLevelCache");
				if (surface.size() != aquifer.surfaceKeys.length) {
					problems.add("aquifer surface cache size: vanilla " + surface.size() + " rust " + aquifer.surfaceKeys.length);
				} else {
					for (int k = 0; k < aquifer.surfaceKeys.length; k++) {
						if (!surface.containsKey(aquifer.surfaceKeys[k]) || surface.get(aquifer.surfaceKeys[k]) != aquifer.surfaceValues[k]) {
							problems.add("aquifer surface level differs");
							break;
						}
					}
				}
			}
			return problems;
		}
	}

	private static final class CellState {
		DensityVolume volume;
		float[] buffer;
		long valueKey;
		float value;
	}

	private static final class AquiferState {
		Aquifer.FluidStatus[] status;
		long[] location;
		long[] surfaceKeys;
		int[] surfaceValues;
		boolean shouldSchedule;
	}

	/** Reflection into vanilla's private density-cache and aquifer state. */
	private static final class Reflect {
		private static final Map<String, Field> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
		private static volatile Method getCacheCell;

		static SamplerContext context(NoiseChunk noiseChunk) {
			Object samplers = noiseChunk.cachingSamplers();
			for (Field field : samplers.getClass().getDeclaredFields()) {
				if (field.getType() == SamplerContext.class) {
					return (SamplerContext) read(field, samplers);
				}
			}
			throw new IllegalStateException("mcrs: no SamplerContext in " + samplers.getClass());
		}

		/** Encodes a Beardifier for Rust; see parse_beard in crates/mcrs-jvm/src/worldgen.rs. */
		static int[] beard(Beardifier beardifier) {
			it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
			net.minecraft.world.level.levelgen.structure.BoundingBox box = get(beardifier, "affectedBox");
			if (box == null) {
				out.addElements(0, new int[] {0, 0, 0, 0, 0, 0, 0});
			} else {
				out.addElements(0, new int[] {1, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
			}
			List<Beardifier.Rigid> pieces = get(beardifier, "pieces");
			out.add(pieces.size());
			for (Beardifier.Rigid rigid : pieces) {
				var b = rigid.box();
				out.addElements(out.size(), new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(),
						rigid.terrainAdjustment().ordinal(), rigid.groundLevelDelta()});
			}
			List<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction> junctions = get(beardifier, "junctions");
			out.add(junctions.size());
			for (var junction : junctions) {
				out.addElements(out.size(), new int[] {junction.getSourceX(), junction.getSourceGroundY(), junction.getSourceZ()});
			}
			return out.toIntArray();
		}

		static Object cacheCell(SamplerContext context, int id) {
			try {
				Method m = getCacheCell;
				if (m == null) {
					m = SamplerContext.class.getDeclaredMethod("getCacheCell", int.class);
					m.setAccessible(true);
					getCacheCell = m;
				}
				return m.invoke(context, id);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		static ScopedDensityBuffer cellBuffer(Object cell) {
			return get(cell, "buffer");
		}

		static void setCell(Object cell, DensityVolume volume, ScopedDensityBuffer buffer, long key, float value) {
			set(cell, "volume", volume);
			set(cell, "buffer", buffer);
			set(cell, "valueKey", key);
			set(cell, "value", value);
		}

		private static Field field(Class<?> type, String name) {
			return FIELDS.computeIfAbsent(type.getName() + "#" + name, k -> {
				for (Class<?> c = type; c != null; c = c.getSuperclass()) {
					try {
						Field f = c.getDeclaredField(name);
						f.setAccessible(true);
						return f;
					} catch (NoSuchFieldException ignored) {
						// keep looking in superclasses
					}
				}
				throw new IllegalStateException("mcrs: " + type.getName() + "." + name + " missing");
			});
		}

		private static Object read(Field field, Object target) {
			try {
				field.setAccessible(true);
				return field.get(target);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}

		@SuppressWarnings("unchecked")
		static <T> T get(Object target, String name) {
			return (T) read(field(target.getClass(), name), target);
		}

		static void set(Object target, String name, Object value) {
			try {
				field(target.getClass(), name).set(target, value);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
