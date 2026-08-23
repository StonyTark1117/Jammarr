package stonytark.jammarr.core.protocol;

import java.util.UUID;

public interface WireOutput {
    void writeVarInt(int value);
    void writeVarLong(long value);
    void writeLong(long value);
    void writeBoolean(boolean value);
    void writeUtf(String value, int maximumCharacters);
    void writeUuid(UUID value);
    void writeByteArray(byte[] value, int maximumBytes);
}
