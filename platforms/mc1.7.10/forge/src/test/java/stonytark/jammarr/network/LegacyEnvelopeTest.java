package stonytark.jammarr.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.TransportPackets;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyEnvelopeTest {
    @Test
    void carriesCanonicalClientHelloBytes() {
        LegacyEnvelope outgoing = LegacyEnvelope.encode(LegacyPacketTypes.CLIENT_HELLO, new ControlPackets.ClientHello(5));
        ByteBuf buffer = Unpooled.buffer();
        outgoing.toBytes(buffer);

        LegacyEnvelope incoming = new LegacyEnvelope();
        incoming.fromBytes(buffer);
        ControlPackets.ClientHello decoded = (ControlPackets.ClientHello) incoming.decode(LegacyPacketTypes.Direction.SERVERBOUND);

        assertEquals(LegacyPacketTypes.CLIENT_HELLO.id(), incoming.messageId());
        assertArrayEquals(new byte[] { 5 }, incoming.payload());
        assertEquals(5, decoded.protocolVersion());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void preservesBoundedAudioChunkFields() {
        UUID session = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        byte[] audio = new byte[] { 1, 2, 3, 4 };
        LegacyEnvelope outgoing = LegacyEnvelope.encode(LegacyPacketTypes.AUDIO_CHUNK,
                new TransportPackets.AudioChunk(session, 42L, 7, 900L, "abcd", audio));

        TransportPackets.AudioChunk decoded = (TransportPackets.AudioChunk) outgoing.decode(LegacyPacketTypes.Direction.CLIENTBOUND);
        assertEquals(session, decoded.sessionId());
        assertEquals(42L, decoded.requestId());
        assertEquals(7, decoded.index());
        assertEquals(900L, decoded.startMs());
        assertEquals("abcd", decoded.sha256());
        assertArrayEquals(audio, decoded.data());
    }

    @Test
    void rejectsWrongDirectionUnknownIdsAndTrailingBytes() {
        LegacyEnvelope hello = LegacyEnvelope.encode(LegacyPacketTypes.CLIENT_HELLO, new ControlPackets.ClientHello(5));
        assertThrows(ProtocolException.class, () -> hello.decode(LegacyPacketTypes.Direction.CLIENTBOUND));

        ByteBuf unknown = Unpooled.buffer();
        cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(unknown, 127, 5);
        cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(unknown, 0, 5);
        assertThrows(ProtocolException.class, () -> new LegacyEnvelope().fromBytes(unknown));

        byte[] validPayload = hello.payload();
        ByteBuf trailing = Unpooled.buffer();
        cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(trailing, hello.messageId(), 5);
        cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(trailing, validPayload.length, 5);
        trailing.writeBytes(validPayload);
        trailing.writeByte(99);
        assertThrows(ProtocolException.class, () -> new LegacyEnvelope().fromBytes(trailing));
    }

    @Test
    void rejectsOversizedDeclaredPayload() {
        ByteBuf oversized = Unpooled.buffer();
        cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(oversized, LegacyPacketTypes.AUDIO_CHUNK.id(), 5);
        cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(oversized, LegacyEnvelope.MAX_ENVELOPE_BYTES + 1, 5);
        assertThrows(ProtocolException.class, () -> new LegacyEnvelope().fromBytes(oversized));
    }
}
