package dev.mcrs.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcrs.bridge.Hoppers;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Idle hoppers wait (performance edition, see {@link Hoppers}). */
@Mixin(HopperBlockEntity.class)
public abstract class HopperIdleMixin {
	@Shadow
	private void setCooldown(int time) {
	}

	@WrapOperation(method = "pushItemsTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z"))
	private static boolean mcrs$idle(Level level, BlockPos pos, BlockState state, HopperBlockEntity entity, BooleanSupplier action, Operation<Boolean> original) {
		boolean moved = original.call(level, pos, state, entity, action);
		if (!moved && Hoppers.ENABLED && !level.isClientSide() && state.getValue(HopperBlock.ENABLED)) {
			((HopperIdleMixin) (Object) entity).setCooldown(Hoppers.IDLE_TICKS);
		}
		return moved;
	}
}
