package dev.mcrs.bridge.mixin;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RegionFile.class)
public interface RegionFileInvoker {
	@Accessor("version")
	RegionFileVersion mcrs$version();

	@Invoker("write")
	void mcrs$write(ChunkPos pos, ByteBuffer data) throws IOException;
}
