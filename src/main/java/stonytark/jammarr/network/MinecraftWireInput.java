package stonytark.jammarr.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import stonytark.jammarr.core.protocol.WireInput;

import java.util.UUID;

final class MinecraftWireInput implements WireInput {
    private final RegistryFriendlyByteBuf buffer;
    MinecraftWireInput(RegistryFriendlyByteBuf buffer) { this.buffer = buffer; }
    @Override public int readVarInt() { return buffer.readVarInt(); }
    @Override public long readVarLong() { return buffer.readVarLong(); }
    @Override public long readLong() { return buffer.readLong(); }
    @Override public boolean readBoolean() { return buffer.readBoolean(); }
    @Override public String readUtf(int maximumCharacters) { return buffer.readUtf(maximumCharacters); }
    @Override public UUID readUuid() { return buffer.readUUID(); }
    @Override public byte[] readByteArray(int maximumBytes) { return buffer.readByteArray(maximumBytes); }
}
