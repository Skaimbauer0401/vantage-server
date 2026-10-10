package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.FastEntities;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code TrackedEntity.updatePlayer} returns at once for an entity beyond the player's view
 * distance that the player does not see: the visible range is at most the view distance, so
 * vanilla would decide "not visible" and its {@code removePlayer} would find nothing to remove.
 * Every player move runs this for every tracked entity.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
	@Shadow
	@Final
	private Entity entity;

	@Shadow
	@Final
	private Set<ServerPlayerConnection> seenBy;

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void mcrs$farAndUnseen(ServerPlayer player, CallbackInfo ci) {
		if (!FastEntities.ENABLED || player == entity) {
			return;
		}
		double dx = player.getX() - entity.getX();
		double dz = player.getZ() - entity.getZ();
		int viewDistance = ((ChunkMapViewAccessor) ((ServerLevel) entity.level()).getChunkSource().chunkMap).mcrs$playerViewDistance(player);
		double maxRange = viewDistance * 16;
		if (dx * dx + dz * dz > maxRange * maxRange && !seenBy.contains(player.connection)) {
			ci.cancel();
		}
	}
}
