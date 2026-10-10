package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cache of {@code BiomeManager.getBiome} for a server level's own manager, on the server thread
 * (spawning asks for the same positions repeatedly). The answer only depends on stored chunk
 * biomes (or the generator's, for chunks that are not there), so the whole cache is cleared
 * whenever a chunk loads, unloads or changes status and whenever a chunk's biomes are refilled.
 */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerCacheMixin implements FastBlocks.BiomeCache {
	@Unique
	private static final int MCRS_SIZE = 4096;

	@Shadow
	@Final
	private BiomeResolver noiseBiomeSource;

	@Unique
	private static final boolean VERIFY = Boolean.getBoolean("mcrs.biomeCache.verify");
	@Unique
	private static final java.util.concurrent.atomic.AtomicLong VERIFIED = FastBlocks.BIOME_VERIFIED;
	@Unique
	private static final java.util.concurrent.atomic.AtomicLong MISMATCHED = FastBlocks.BIOME_MISMATCHED;

	@Unique
	private Holder<Biome> mcrs$expected;
	@Unique
	private long[] mcrs$keys;
	@Unique
	private Holder<Biome>[] mcrs$biomes;
	@Unique
	private Thread mcrs$owner;
	@Unique
	private boolean mcrs$checked;

	@Unique
	private static int mcrs$slot(long key) {
		return (int) ((key * 0x9E3779B97F4A7C15L) >>> 52);
	}

	@SuppressWarnings("unchecked")
	@Unique
	private boolean mcrs$active() {
		if (!mcrs$checked) {
			mcrs$checked = true;
			if (FastBlocks.ENABLED && noiseBiomeSource instanceof ServerLevel level && level.getBiomeManager() == (Object) this) {
				mcrs$owner = level.getServer().getRunningThread();
				mcrs$keys = new long[MCRS_SIZE];
				mcrs$biomes = new Holder[MCRS_SIZE];
			}
		}
		return mcrs$owner != null && Thread.currentThread() == mcrs$owner;
	}

	@Unique
	private static boolean mcrs$packable(int x, int y, int z) {
		return x >= -33554432 && x <= 33554431 && z >= -33554432 && z <= 33554431 && y >= -2048 && y <= 2047;
	}

	@Inject(method = "getBiome(III)Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true)
	private void mcrs$cached(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> cir) {
		if (mcrs$active() && mcrs$packable(x, y, z)) {
			long key = BlockPos.asLong(x, y, z);
			int slot = mcrs$slot(key);
			Holder<Biome> biome = mcrs$biomes[slot];
			if (biome != null && mcrs$keys[slot] == key) {
				if (VERIFY) {
					mcrs$expected = biome;
				} else {
					cir.setReturnValue(biome);
				}
			}
		}
	}

	@Inject(method = "getBiome(III)Lnet/minecraft/core/Holder;", at = @At("RETURN"))
	private void mcrs$store(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> cir) {
		if (mcrs$owner != null && Thread.currentThread() == mcrs$owner && mcrs$packable(x, y, z)) {
			if (mcrs$expected != null) {
				if (mcrs$expected != cir.getReturnValue()) {
					MISMATCHED.incrementAndGet();
				} else {
					VERIFIED.incrementAndGet();
				}
				mcrs$expected = null;
			}
			long key = BlockPos.asLong(x, y, z);
			int slot = mcrs$slot(key);
			mcrs$keys[slot] = key;
			mcrs$biomes[slot] = cir.getReturnValue();
		}
	}

	@Override
	public void mcrs$clearBiomeCache() {
		if (mcrs$biomes != null) {
			java.util.Arrays.fill(mcrs$biomes, null);
		}
	}
}
