package stonytark.jammarr.core.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ByteArrayWireOutput implements WireOutput {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Override public void writeVarInt(int value) {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    @Override public void writeVarLong(long value) {
        while ((value & ~0x7fL) != 0) {
            output.write((int) (value & 0x7fL) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }

    @Override public void writeLong(long value) { write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()); }
    @Override public void writeBoolean(boolean value) { output.write(value ? 1 : 0); }

    @Override public void writeUtf(String value, int maximumCharacters) {
        if (value == null) throw new ProtocolException("UTF-8 value is null");
        if (value.length() > maximumCharacters) throw new ProtocolException("UTF-8 value exceeds character limit");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumCharacters * 3) throw new ProtocolException("UTF-8 value exceeds byte limit");
        writeVarInt(bytes.length);
        write(bytes);
    }

    @Override public void writeUuid(UUID value) {
        if (value == null) throw new ProtocolException("UUID is null");
        writeLong(value.getMostSignificantBits());
        writeLong(value.getLeastSignificantBits());
    }

    @Override public void writeByteArray(byte[] value, int maximumBytes) {
        if (value == null || value.length > maximumBytes) throw new ProtocolException("Byte array exceeds limit");
        writeVarInt(value.length);
        write(value);
    }

    public byte[] toByteArray() { return output.toByteArray(); }
    private void write(byte[] value) { output.write(value, 0, value.length); }
}
