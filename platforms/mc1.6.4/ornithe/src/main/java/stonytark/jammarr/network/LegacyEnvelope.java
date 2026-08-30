package stonytark.jammarr.network;

import net.ornithemc.osl.networking.api.PacketBuffer;
import stonytark.jammarr.core.protocol.ByteArrayWireInput;
import stonytark.jammarr.core.protocol.ByteArrayWireOutput;
import stonytark.jammarr.core.protocol.ProtocolException;

/** One bounded Ornithe payload containing a protocol-6 message and canonical wire bytes. */
public final class LegacyEnvelope {
    public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;

    private final int messageId;
    private final byte[] payload;

    private LegacyEnvelope(int messageId, byte[] payload) {
        this.messageId = messageId;
        this.payload = payload;
    }

    public static <T> LegacyEnvelope encode(LegacyPacketTypes.Type<T> type, T value) {
        if (type == null) throw new IllegalArgumentException("type");
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        type.codec().encode(output, value);
        byte[] payload = output.toByteArray();
        if (payload.length > MAX_ENVELOPE_BYTES) throw new ProtocolException("Legacy packet exceeds envelope limit");
        return new LegacyEnvelope(type.id(), payload);
    }

    public static LegacyEnvelope read(PacketBuffer buffer) {
        int id = buffer.readVarInt();
        if (LegacyPacketTypes.byId(id) == null) throw new ProtocolException("Unknown legacy packet ID " + id);
        int length = buffer.readVarInt();
        if (length < 0 || length > MAX_ENVELOPE_BYTES || length != buffer.readableBytes()) {
            throw new ProtocolException("Invalid legacy packet length " + length);
        }
        byte[] payload = new byte[length];
        buffer.readBytes(payload);
        return new LegacyEnvelope(id, payload);
    }

    public void write(PacketBuffer buffer) {
        type();
        buffer.writeVarInt(messageId);
        buffer.writeVarInt(payload.length);
        buffer.writeBytes(payload);
    }

    public Object decode(LegacyPacketTypes.Direction expectedDirection) {
        LegacyPacketTypes.Type<?> type = type();
        if (type.direction() != expectedDirection) {
            throw new ProtocolException("Legacy packet " + type.name() + " arrived in the wrong direction");
        }
        ByteArrayWireInput input = new ByteArrayWireInput(payload);
        Object value = type.codec().decode(input);
        if (input.remaining() != 0) throw new ProtocolException("Legacy packet " + type.name() + " has trailing bytes");
        return value;
    }

    public LegacyPacketTypes.Type<?> type() {
        LegacyPacketTypes.Type<?> type = LegacyPacketTypes.byId(messageId);
        if (type == null) throw new ProtocolException("Unknown legacy packet ID " + messageId);
        return type;
    }

    public int messageId() { return messageId; }
    public byte[] payload() { return payload.clone(); }
}
