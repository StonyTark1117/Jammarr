package stonytark.jammarr.network;

import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import stonytark.jammarr.core.protocol.ByteArrayWireInput;
import stonytark.jammarr.core.protocol.ByteArrayWireOutput;
import stonytark.jammarr.core.protocol.ProtocolException;

/** One bounded FML payload containing a protocol-6 message identifier and canonical wire bytes. */
public class LegacyEnvelope implements IMessage {
    public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;

    private int messageId;
    private byte[] payload;

    public LegacyEnvelope() {
        payload = new byte[0];
    }

    protected LegacyEnvelope(int messageId, byte[] payload) {
        this.messageId = messageId;
        this.payload = payload;
    }

    public static <T> LegacyEnvelope encode(LegacyPacketTypes.Type<T> type, T value) {
        if (type == null) throw new IllegalArgumentException("type");
        return new LegacyEnvelope(type.id(), encodePayload(type, value));
    }

    static <T> byte[] encodePayload(LegacyPacketTypes.Type<T> type, T value) {
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        type.codec().encode(output, value);
        byte[] payload = output.toByteArray();
        if (payload.length > MAX_ENVELOPE_BYTES) throw new ProtocolException("Legacy packet exceeds envelope limit");
        return payload;
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

    @Override
    public void fromBytes(ByteBuf buffer) {
        int id = ByteBufUtils.readVarInt(buffer, 5);
        if (LegacyPacketTypes.byId(id) == null) throw new ProtocolException("Unknown legacy packet ID " + id);
        int length = ByteBufUtils.readVarInt(buffer, 5);
        if (length < 0 || length > MAX_ENVELOPE_BYTES || length > buffer.readableBytes()) {
            throw new ProtocolException("Invalid legacy packet length " + length);
        }
        if (length != buffer.readableBytes()) throw new ProtocolException("Legacy packet has trailing envelope bytes");
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        messageId = id;
        payload = bytes;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        if (payload == null || payload.length > MAX_ENVELOPE_BYTES) {
            throw new ProtocolException("Legacy packet exceeds envelope limit");
        }
        type();
        ByteBufUtils.writeVarInt(buffer, messageId, 5);
        ByteBufUtils.writeVarInt(buffer, payload.length, 5);
        buffer.writeBytes(payload);
    }
}
