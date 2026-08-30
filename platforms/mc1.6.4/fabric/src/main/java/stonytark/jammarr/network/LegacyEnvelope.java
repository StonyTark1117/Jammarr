package stonytark.jammarr.network;

import stonytark.jammarr.core.protocol.ByteArrayWireInput;
import stonytark.jammarr.core.protocol.ByteArrayWireOutput;
import stonytark.jammarr.core.protocol.ProtocolException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Bounded custom-payload envelope containing a protocol-6 message identifier and canonical wire bytes. */
public final class LegacyEnvelope {
    public static final int MAX_ENVELOPE_BYTES = 32_760;

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
        if (payload.length + 8 > MAX_ENVELOPE_BYTES) {
            throw new ProtocolException("Legacy packet exceeds custom-payload limit");
        }
        return new LegacyEnvelope(type.id(), payload);
    }

    public static LegacyEnvelope read(byte[] packet) {
        if (packet == null || packet.length < 8 || packet.length > MAX_ENVELOPE_BYTES) {
            throw new ProtocolException("Invalid legacy packet length");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
            int id = input.readInt();
            if (LegacyPacketTypes.byId(id) == null) throw new ProtocolException("Unknown legacy packet ID " + id);
            int length = input.readInt();
            if (length < 0 || length > MAX_ENVELOPE_BYTES - 8 || length != input.available()) {
                throw new ProtocolException("Invalid legacy packet payload length " + length);
            }
            byte[] payload = new byte[length];
            input.readFully(payload);
            return new LegacyEnvelope(id, payload);
        } catch (IOException error) {
            throw new ProtocolException("Unable to read legacy packet");
        }
    }

    public byte[] write() {
        type();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 8);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(messageId);
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new ProtocolException("Unable to write legacy packet");
        }
    }

    public Object decode(LegacyPacketTypes.Direction expectedDirection) {
        LegacyPacketTypes.Type<?> type = type();
        if (type.direction() != expectedDirection) throw new ProtocolException("Legacy packet direction mismatch");
        ByteArrayWireInput input = new ByteArrayWireInput(payload);
        Object decoded = type.codec().decode(input);
        if (input.remaining() != 0) throw new ProtocolException("Trailing legacy packet bytes");
        return decoded;
    }

    public LegacyPacketTypes.Type<?> type() {
        LegacyPacketTypes.Type<?> type = LegacyPacketTypes.byId(messageId);
        if (type == null) throw new ProtocolException("Unknown legacy packet ID " + messageId);
        return type;
    }

    int messageId() { return messageId; }
    byte[] payload() { return payload.clone(); }
}
