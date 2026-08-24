package stonytark.jammarr.core.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class ByteArrayWireInput implements WireInput {
    private final byte[] data;
    private int index;

    public ByteArrayWireInput(byte[] data) { this.data = data == null ? new byte[0] : data; }

    @Override public int readVarInt() {
        int value = 0;
        int position = 0;
        while (true) {
            byte current = readByte();
            value |= (current & 0x7f) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            if (position >= 35) throw new ProtocolException("VarInt is too large");
        }
    }

    @Override public long readVarLong() {
        long value = 0;
        int position = 0;
        while (true) {
            byte current = readByte();
            value |= (long) (current & 0x7f) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            if (position >= 70) throw new ProtocolException("VarLong is too large");
        }
    }

    @Override public long readLong() { return buffer(8).getLong(); }
    @Override public boolean readBoolean() { return readByte() != 0; }

    @Override public String readUtf(int maximumCharacters) {
        int byteLength = readVarInt();
        if (byteLength < 0 || byteLength > maximumCharacters * 3) throw new ProtocolException("UTF-8 value exceeds byte limit");
        require(byteLength);
        String value = new String(data, index, byteLength, StandardCharsets.UTF_8);
        index += byteLength;
        if (value.length() > maximumCharacters) throw new ProtocolException("UTF-8 value exceeds character limit");
        return value;
    }

    @Override public UUID readUuid() { return new UUID(readLong(), readLong()); }

    @Override public byte[] readByteArray(int maximumBytes) {
        int length = readVarInt();
        if (length < 0 || length > maximumBytes) throw new ProtocolException("Byte array exceeds limit");
        require(length);
        byte[] value = Arrays.copyOfRange(data, index, index + length);
        index += length;
        return value;
    }

    public int remaining() { return data.length - index; }

    private byte readByte() { require(1); return data[index++]; }
    private ByteBuffer buffer(int length) {
        require(length);
        ByteBuffer value = ByteBuffer.wrap(data, index, length).order(ByteOrder.BIG_ENDIAN);
        index += length;
        return value;
    }
    private void require(int length) {
        if (length < 0 || index > data.length - length) throw new ProtocolException("Packet ended before the declared field length");
    }
}
