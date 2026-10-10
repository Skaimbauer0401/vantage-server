package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastBlocks;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The {@link FastBlocks} cache in front of {@code Level.getBlockState}. */
@Mixin(Level.class)
public abstract class LevelBlockCacheMixin implements FastBlocks.Cache {
	@Unique
	private static final int MCRS_SIZE = 4096;

	@Unique
	private final long[] mcrs$keys = new long[MCRS_SIZE];
	@Unique
	private final BlockState[] mcrs$states = new BlockState[MCRS_SIZE];
	/** The server thread, once known; null while the cache is off for this level. */
	@Unique
	private Thread mcrs$owner;
	@Unique
	private boolean mcrs$checked;

	@Shadow
	public abstract boolean isInValidBounds(BlockPos pos);

	@Shadow
	public abstract boolean isDebug();

	@Unique
	private static int mcrs$slot(long key) {
		return (int) ((key * 0x9E3779B97F4A7C15L) >>> 52);
	}

	@Unique
	private boolean mcrs$active() {
		if (!mcrs$checked) {
			mcrs$checked = true;
			if (FastBlocks.ENABLED && (Object) this instanceof ServerLevel level && !isDebug()) {
				mcrs$owner = level.getServer().getRunningThread();
			}
		}
		return mcrs$owner != null && Thread.currentThread() == mcrs$owner;
	}

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void mcrs$cachedState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (mcrs$active()) {
			long key = pos.asLong();
			int slot = mcrs$slot(key);
			BlockState state = mcrs$states[slot];
			if (state != null && mcrs$keys[slot] == key) {
				cir.setReturnValue(state);
			}
		}
	}

	@Inject(method = "getBlockState", at = @At("RETURN"))
	private void mcrs$storeState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (mcrs$owner != null && Thread.currentThread() == mcrs$owner && isInValidBounds(pos)) {
			long key = pos.asLong();
			int slot = mcrs$slot(key);
			mcrs$keys[slot] = key;
			mcrs$states[slot] = cir.getReturnValue();
		}
	}

	@Override
	public void mcrs$invalidate(long key) {
		int slot = mcrs$slot(key);
		if (mcrs$keys[slot] == key) {
			mcrs$states[slot] = null;
		}
	}

	@Override
	public void mcrs$clearBlockCache() {
		Arrays.fill(mcrs$states, null);
	}
}
