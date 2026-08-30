package stonytark.jammarr.network;

import stonytark.jammarr.core.protocol.ByteArrayWireInput;
import stonytark.jammarr.core.protocol.ByteArrayWireOutput;
import stonytark.jammarr.core.protocol.ProtocolException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** One bounded StationAPI payload containing a protocol-6 message and canonical wire bytes. */
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

    public static LegacyEnvelope read(byte[] bytes) {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_ENVELOPE_BYTES + 8) {
            throw new ProtocolException("Invalid legacy envelope size");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int id = input.readInt();
            if (LegacyPacketTypes.byId(id) == null) throw new ProtocolException("Unknown legacy packet ID " + id);
            int length = input.readInt();
            if (length < 0 || length > MAX_ENVELOPE_BYTES || length != input.available()) {
                throw new ProtocolException("Invalid legacy packet length " + length);
            }
            byte[] payload = new byte[length];
            input.readFully(payload);
            return new LegacyEnvelope(id, payload);
        } catch (IOException error) {
            throw new ProtocolException("Unable to read legacy packet", error);
        }
    }

    public byte[] toByteArray() {
        type();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 8);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(messageId);
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new ProtocolException("Unable to write legacy packet", error);
        }
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
