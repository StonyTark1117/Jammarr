package stonytark.jammarr.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.WireOutput;

import java.util.UUID;

final class MinecraftWireOutput implements WireOutput {
    private final RegistryFriendlyByteBuf buffer;
    MinecraftWireOutput(RegistryFriendlyByteBuf buffer) { this.buffer = buffer; }
    @Override public void writeVarInt(int value) { buffer.writeVarInt(value); }
    @Override public void writeVarLong(long value) { buffer.writeVarLong(value); }
    @Override public void writeLong(long value) { buffer.writeLong(value); }
    @Override public void writeBoolean(boolean value) { buffer.writeBoolean(value); }
    @Override public void writeUtf(String value, int maximumCharacters) { buffer.writeUtf(value, maximumCharacters); }
    @Override public void writeUuid(UUID value) { buffer.writeUUID(value); }
    @Override public void writeByteArray(byte[] value, int maximumBytes) {
        if (value == null || value.length > maximumBytes) throw new ProtocolException("Byte array exceeds limit");
        buffer.writeByteArray(value);
    }
}
