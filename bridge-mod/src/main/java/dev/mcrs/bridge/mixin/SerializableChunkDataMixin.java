package dev.mcrs.bridge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.mcrs.bridge.ChunkIo;
import net.minecraft.core.Holder;
import java.util.List;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builds section containers from the global ids Rust put in {@code mcrs:palette}
 * instead of decoding every palette entry through DFU codecs.
 *
 * <p>{@code lambda$parse$3} decodes {@code block_states} and {@code lambda$parse$5}
 * decodes {@code biomes} (Minecraft 26.3). If building from ids fails for any reason,
 * the vanilla palette is restored and vanilla decodes the section, so even errors
 * look exactly like vanilla's.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
	@Inject(method = "parse", at = @At("HEAD"))
	private static void mcrs$rememberFactory(LevelHeightAccessor level, PalettedContainerFactory factory, CompoundTag tag,
			CallbackInfoReturnable<SerializableChunkData> cir) {
		ChunkIo.PARSE_FACTORY.set(factory);
	}

	@Inject(method = "lambda$parse$3", at = @At("HEAD"), cancellable = true)
	private static void mcrs$blockStates(Codec<PalettedContainer<BlockState>> codec, ChunkPos pos, int sectionY,
			CompoundTag tag, CallbackInfoReturnable<PalettedContainer<BlockState>> cir) {
		PalettedContainerFactory factory = ChunkIo.PARSE_FACTORY.get();
		if (factory == null) {
			return;
		}
		Strategy<BlockState> strategy = factory.blockStatesStrategy();
		PalettedContainer<BlockState> fast = mcrs$unpack(strategy, tag,
				palette -> BlockState.CODEC.listOf().encodeStart(NbtOps.INSTANCE, palette).getOrThrow());
		if (fast == null) {
			return;
		}
		if (ChunkIo.VERIFY && tag.contains("palette")) {
			PalettedContainer<BlockState> vanilla = codec.parse(NbtOps.INSTANCE, mcrs$withoutIds(tag)).getOrThrow();
			ChunkIo.verify(vanilla, fast, 4096, "block_states", pos, sectionY);
		}
		cir.setReturnValue(fast);
	}

	@Inject(method = "lambda$parse$5", at = @At("HEAD"), cancellable = true)
	private static void mcrs$biomes(Codec<PalettedContainerRO<Holder<Biome>>> codec, ChunkPos pos, int sectionY,
			CompoundTag tag, CallbackInfoReturnable<PalettedContainerRO<Holder<Biome>>> cir) {
		PalettedContainerFactory factory = ChunkIo.PARSE_FACTORY.get();
		if (factory == null) {
			return;
		}
		Strategy<Holder<Biome>> strategy = factory.biomeStrategy();
		PalettedContainer<Holder<Biome>> fast = mcrs$unpack(strategy, tag, SerializableChunkDataMixin::mcrs$biomeIds);
		if (fast == null) {
			return;
		}
		if (ChunkIo.VERIFY && tag.contains("palette")) {
			PalettedContainerRO<Holder<Biome>> vanilla = codec.parse(NbtOps.INSTANCE, mcrs$withoutIds(tag)).getOrThrow();
			ChunkIo.verify(vanilla, fast, 64, "biomes", pos, sectionY);
		}
		cir.setReturnValue(fast);
	}

	/**
	 * Builds a container from {@code mcrs:palette}. Returns null when vanilla should
	 * decode the section, after putting the vanilla palette back if Rust removed it.
	 */
	@Unique
	private static <T> PalettedContainer<T> mcrs$unpack(Strategy<T> strategy, CompoundTag tag,
			Function<List<T>, Tag> encodePalette) {
		PalettedContainerRO.PackedData<T> packed = ChunkIo.packedData(strategy, tag);
		if (packed == null) {
			return null;
		}
		DataResult<PalettedContainer<T>> result = PalettedContainer.unpack(strategy, packed);
		if (result.isSuccess()) {
			return result.getOrThrow();
		}
		if (!tag.contains("palette")) {
			tag.put("palette", encodePalette.apply(packed.paletteEntries()));
		}
		tag.remove(ChunkIo.PALETTE_KEY);
		return null;
	}

	@Unique
	private static Tag mcrs$biomeIds(List<Holder<Biome>> palette) {
		ListTag list = new ListTag();
		for (Holder<Biome> biome : palette) {
			list.add(StringTag.valueOf(biome.unwrapKey().orElseThrow().identifier().toString()));
		}
		return list;
	}

	@Unique
	private static CompoundTag mcrs$withoutIds(CompoundTag tag) {
		CompoundTag copy = tag.copy();
		copy.remove(ChunkIo.PALETTE_KEY);
		return copy;
	}
}
