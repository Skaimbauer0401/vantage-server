package dev.mcrs.bridge;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;

/**
 * {@code OreFeature.doPlace} with the same block visiting order, the same random calls and the
 * same writes, minus per-block overhead: the block getter for the air check is created once
 * instead of per block, the build height test uses precomputed bounds, sections are looked up
 * only when the section changes, and the write-zone check is done once per chunk (it only
 * depends on the chunk unless the center chunk is upgrading; a refusal is still asked of the
 * region every time, so it logs as vanilla does). {@code -Dmcrs.fastOres=false} turns it off.
 */
public final class FastOres {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcrs.fastOres"));
	/** Runs each placement both ways and compares writes, result and random state. */
	public static final boolean VERIFY = Boolean.getBoolean("mcrs.fastOres.verify");
	public static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();
	public static final java.util.concurrent.atomic.AtomicLong MISMATCHED = new java.util.concurrent.atomic.AtomicLong();
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

	/** Section writes of the placement running on this thread, while verifying. */
	public static final ThreadLocal<java.util.List<Object[]>> WRITES = new ThreadLocal<>();

	/** State of a verified placement between the fast run (HEAD) and vanilla's (RETURN). */
	public static final class Check {
		public java.util.List<Object[]> fastWrites;
		public StateSnapshot fastRandom;
		public boolean fastResult;
	}

	public static final ThreadLocal<Check> CHECK = new ThreadLocal<>();

	/** Records a section write (called from the section mixin while recording). */
	public static void recordWrite(LevelChunkSection section, int x, int y, int z, BlockState state) {
		java.util.List<Object[]> log = WRITES.get();
		if (log != null) {
			log.add(new Object[] {section, x, y, z, section.getBlockState(x, y, z), state});
		}
	}

	/** HEAD of a verified doPlace: runs the fast version, records it, then undoes it. */
	public static void verifyBefore(OreFeature feature, WorldGenLevel level, RandomSource random, double x0, double x1, double z0, double z1, double y0, double y1,
			int xStart, int yStart, int zStart, int sizeXZ, int sizeY) {
		StateSnapshot before = StateSnapshot.capture(random);
		Check check = new Check();
		check.fastWrites = new java.util.ArrayList<>();
		WRITES.set(check.fastWrites);
		try {
			check.fastResult = doPlace(feature, level, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY);
		} finally {
			WRITES.remove();
		}
		check.fastRandom = StateSnapshot.capture(random);
		for (int i = check.fastWrites.size() - 1; i >= 0; i--) {
			Object[] w = check.fastWrites.get(i);
			((LevelChunkSection) w[0]).setBlockState((Integer) w[1], (Integer) w[2], (Integer) w[3], (BlockState) w[4], false);
		}
		before.restore();
		CHECK.set(check);
		WRITES.set(new java.util.ArrayList<>());
	}

	/** RETURN of a verified doPlace: compares vanilla's run with the fast one. */
	public static void verifyAfter(RandomSource random, boolean vanillaResult) {
		Check check = CHECK.get();
		java.util.List<Object[]> vanillaWrites = WRITES.get();
		CHECK.remove();
		WRITES.remove();
		if (check == null || vanillaWrites == null) {
			return;
		}
		String problem = null;
		if (check.fastResult != vanillaResult) {
			problem = "result " + check.fastResult + " vs vanilla " + vanillaResult;
		} else if (!StateSnapshot.sameValues(check.fastRandom, StateSnapshot.capture(random))) {
			problem = "random state differs";
		} else if (check.fastWrites.size() != vanillaWrites.size()) {
			problem = "writes " + check.fastWrites.size() + " vs vanilla " + vanillaWrites.size();
		} else {
			for (int i = 0; i < vanillaWrites.size() && problem == null; i++) {
				Object[] a = check.fastWrites.get(i);
				Object[] b = vanillaWrites.get(i);
				for (int k = 0; k < 6; k++) {
					if (k == 4) {
						continue;
					}
					if (!java.util.Objects.equals(a[k], b[k]) && !(k == 0 && a[k] == b[k])) {
						problem = "write " + i + " differs";
						break;
					}
				}
			}
		}
		if (problem == null) {
			VERIFIED.incrementAndGet();
		} else if (MISMATCHED.incrementAndGet() <= 20) {
			LOGGER.warn("[mcrs] fast ore placement differs from vanilla: {}", problem);
		}
	}

	/** Implemented by the region accessor. */
	public interface Region {
		boolean mcrs$centerUpgrading();
	}

	private FastOres() {
	}

	public static boolean doPlace(OreFeature feature, WorldGenLevel level, RandomSource random, double x0, double x1, double z0, double z1, double y0, double y1,
			int xStart, int yStart, int zStart, int sizeXZ, int sizeY) {
		int size = feature.size();
		List<BlockReplacement> targets = feature.targetStates();
		int placed = 0;
		long[] tested = new long[(sizeXZ * sizeY * sizeXZ + 63) >>> 6];
		BlockPos.MutableBlockPos orePos = new BlockPos.MutableBlockPos();
		double[] data = new double[size * 4];

		for (int i = 0; i < size; i++) {
			float step = (float) i / size;
			double xx = Mth.lerp(step, x0, x1);
			double yy = Mth.lerp(step, y0, y1);
			double zz = Mth.lerp(step, z0, z1);
			double ss = random.nextDouble() * size / 16.0;
			double r = ((Mth.sin((float) Math.PI * step) + 1.0F) * ss + 1.0) / 2.0;
			data[i * 4 + 0] = xx;
			data[i * 4 + 1] = yy;
			data[i * 4 + 2] = zz;
			data[i * 4 + 3] = r;
		}

		for (int i1 = 0; i1 < size - 1; i1++) {
			if (!(data[i1 * 4 + 3] <= 0.0)) {
				for (int i2 = i1 + 1; i2 < size; i2++) {
					if (!(data[i2 * 4 + 3] <= 0.0)) {
						double dx = data[i1 * 4 + 0] - data[i2 * 4 + 0];
						double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
						double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
						double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
						if (dr * dr > dx * dx + dy * dy + dz * dz) {
							if (dr > 0.0) {
								data[i2 * 4 + 3] = -1.0;
							} else {
								data[i1 * 4 + 3] = -1.0;
							}
						}
					}
				}
			}
		}

		int minBuildY = level.getMinY();
		int maxBuildY = level.getMaxY();
		boolean writeByChunk = level instanceof WorldGenRegion region && !((Region) region).mcrs$centerUpgrading();
		long writableChunk = Long.MIN_VALUE;
		try (BulkSectionAccess sectionGetter = new BulkSectionAccess(level)) {
			java.util.function.Function<BlockPos, BlockState> blockGetter = sectionGetter::getBlockState;
			long sectionKey = Long.MIN_VALUE;
			LevelChunkSection section = null;
			for (int i = 0; i < size; i++) {
				double r = data[i * 4 + 3];
				if (!(r < 0.0)) {
					double xx = data[i * 4 + 0];
					double yy = data[i * 4 + 1];
					double zz = data[i * 4 + 2];
					int xMin = Math.max(Mth.floor(xx - r), xStart);
					int yMin = Math.max(Mth.floor(yy - r), yStart);
					int zMin = Math.max(Mth.floor(zz - r), zStart);
					int xMax = Math.max(Mth.floor(xx + r), xMin);
					int yMax = Math.max(Mth.floor(yy + r), yMin);
					int zMax = Math.max(Mth.floor(zz + r), zMin);

					for (int x = xMin; x <= xMax; x++) {
						double xd = (x + 0.5 - xx) / r;
						if (xd * xd < 1.0) {
							for (int y = yMin; y <= yMax; y++) {
								double yd = (y + 0.5 - yy) / r;
								if (xd * xd + yd * yd < 1.0) {
									for (int z = zMin; z <= zMax; z++) {
										double zd = (z + 0.5 - zz) / r;
										if (xd * xd + yd * yd + zd * zd < 1.0 && y >= minBuildY && y <= maxBuildY) {
											int bit = x - xStart + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;
											long word = tested[bit >>> 6];
											long mask = 1L << bit;
											if ((word & mask) == 0) {
												tested[bit >>> 6] = word | mask;
												orePos.set(x, y, z);
												boolean writable;
												if (writeByChunk) {
													long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
													if (chunk == writableChunk) {
														writable = true;
													} else {
														writable = level.ensureCanWrite(orePos);
														writableChunk = writable ? chunk : Long.MIN_VALUE;
													}
												} else {
													writable = level.ensureCanWrite(orePos);
												}
												if (writable) {
													long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
													if (key != sectionKey || section == null) {
														section = sectionGetter.getSection(orePos);
														sectionKey = key;
													}
													if (section != null) {
														int sx = x & 15;
														int sy = y & 15;
														int sz = z & 15;
														BlockState blockState = section.getBlockState(sx, sy, sz);
														for (BlockReplacement target : targets) {
															if (feature.canPlaceOre(blockState, blockGetter, random, target, orePos)) {
																section.setBlockState(sx, sy, sz, target.state(), false);
																placed++;
																break;
															}
														}
													}
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
		return placed > 0;
	}
}
