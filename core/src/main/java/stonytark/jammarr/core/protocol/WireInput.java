package stonytark.jammarr.core.protocol;

import java.util.UUID;

public interface WireInput {
    int readVarInt();
    long readVarLong();
    long readLong();
    boolean readBoolean();
    String readUtf(int maximumCharacters);
    UUID readUuid();
    byte[] readByteArray(int maximumBytes);
}
