package dev.mcrs.bridge.mixin;

import dev.mcrs.bridge.NativeBridge;
import dev.mcrs.bridge.NativeCompression;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet compression through libdeflate (see {@link NativeCompression}). */
@Mixin(CompressionEncoder.class)
public abstract class CompressionEncoderMixin {
	@Shadow
	public abstract int getThreshold();

	@Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"), cancellable = true)
	private void mcrs$encode(ChannelHandlerContext ctx, ByteBuf uncompressed, ByteBuf out, CallbackInfo ci) {
		int length = uncompressed.readableBytes();
		if (!NativeCompression.NETWORK || !NativeCompression.available() || length < getThreshold() || length > 8388608) {
			return;
		}
		byte[] input;
		int offset;
		if (uncompressed.hasArray()) {
			input = uncompressed.array();
			offset = uncompressed.arrayOffset() + uncompressed.readerIndex();
		} else {
			input = new byte[length];
			uncompressed.getBytes(uncompressed.readerIndex(), input);
			offset = 0;
		}
		byte[] compressed = NativeCompression.buffer(NativeBridge.zlibBound(length));
		int written = NativeBridge.zlibCompress(NativeCompression.LEVEL, input, offset, length, compressed, 0);
		if (written < 0) {
			return;
		}
		uncompressed.skipBytes(length);
		VarInt.write(out, length);
		out.writeBytes(compressed, 0, written);
		ci.cancel();
	}
}
