package dev.mcrs.bridge;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Big-endian {@link DataInput} over a byte array. Used instead of
 * {@code DataInputStream(ByteArrayInputStream)}, whose every read is a synchronized
 * call; NBT long arrays are read one {@code readLong} at a time.
 */
final class ByteArrayDataInput implements DataInput {
	private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

	private final byte[] data;
	private int pos;

	ByteArrayDataInput(byte[] data) {
		this.data = data;
	}

	private int advance(int n) throws EOFException {
		int start = pos;
		if (n < 0 || n > data.length - start) {
			throw new EOFException();
		}
		pos = start + n;
		return start;
	}

	@Override
	public void readFully(byte[] b) throws IOException {
		readFully(b, 0, b.length);
	}

	@Override
	public void readFully(byte[] b, int off, int len) throws IOException {
		System.arraycopy(data, advance(len), b, off, len);
	}

	@Override
	public int skipBytes(int n) {
		int skipped = Math.max(0, Math.min(n, data.length - pos));
		pos += skipped;
		return skipped;
	}

	@Override
	public boolean readBoolean() throws IOException {
		return readByte() != 0;
	}

	@Override
	public byte readByte() throws IOException {
		return data[advance(1)];
	}

	@Override
	public int readUnsignedByte() throws IOException {
		return readByte() & 0xFF;
	}

	@Override
	public short readShort() throws IOException {
		return (short) SHORT.get(data, advance(2));
	}

	@Override
	public int readUnsignedShort() throws IOException {
		return readShort() & 0xFFFF;
	}

	@Override
	public char readChar() throws IOException {
		return (char) readShort();
	}

	@Override
	public int readInt() throws IOException {
		return (int) INT.get(data, advance(4));
	}

	@Override
	public long readLong() throws IOException {
		return (long) LONG.get(data, advance(8));
	}

	@Override
	public float readFloat() throws IOException {
		return Float.intBitsToFloat(readInt());
	}

	@Override
	public double readDouble() throws IOException {
		return Double.longBitsToDouble(readLong());
	}

	@Override
	public String readLine() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String readUTF() throws IOException {
		return DataInputStream.readUTF(this);
	}
}
