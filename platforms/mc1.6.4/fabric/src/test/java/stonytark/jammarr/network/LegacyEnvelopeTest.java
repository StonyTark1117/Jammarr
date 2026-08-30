package stonytark.jammarr.network;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolGoldenVectors;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.TransportPackets;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyEnvelopeTest {
    @Test
    void carriesCanonicalClientHelloBytes() {
        LegacyEnvelope outgoing = LegacyEnvelope.encode(LegacyPacketTypes.CLIENT_HELLO,
                new ControlPackets.ClientHello(ProtocolLimits.VERSION));
        byte[] packet = outgoing.write();
        LegacyEnvelope incoming = LegacyEnvelope.read(packet);
        ControlPackets.ClientHello decoded = (ControlPackets.ClientHello) incoming.decode(LegacyPacketTypes.Direction.SERVERBOUND);

        assertEquals(LegacyPacketTypes.CLIENT_HELLO.id(), incoming.messageId());
        assertEquals(ProtocolGoldenVectors.CLIENT_HELLO, hex(incoming.payload()));
        assertEquals(ProtocolLimits.VERSION, decoded.protocolVersion());
        assertEquals(stonytark.jammarr.core.protocol.ProtocolCapabilities.SUPPORTED_FEATURES, decoded.features());
        assertEquals(stonytark.jammarr.core.protocol.ProtocolCapabilities.AUDIO_CHUNK_BYTES, decoded.audioChunkBytes());
        assertEquals(stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST, decoded.chunksPerRequest());
        assertEquals(outgoing.payload().length + 8, packet.length);
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
    void legacyAdapterConsumesTheSharedBrowseStationAndChunkVectors() {
        assertEquals(ProtocolGoldenVectors.BROWSE_REQUEST, hex(LegacyEnvelope.encode(
                LegacyPacketTypes.BROWSE_REQUEST,
                new ControlPackets.BrowseRequest(ControlPackets.BrowseKind.SEARCH, "A&B", 2)).payload()));
        assertEquals(ProtocolGoldenVectors.STATION_REQUEST, hex(LegacyEnvelope.encode(
                LegacyPacketTypes.STATION_REQUEST,
                new ControlPackets.StationRequest(ControlPackets.StationAction.START_NOW,
                        stonytark.jammarr.core.model.StationModels.StationType.SONIC_ADVENTURE, false, 12,
                        java.util.Collections.singletonList(new stonytark.jammarr.core.model.StationModels.StationSeed(
                                stonytark.jammarr.core.model.StationModels.ItemKind.TRACK, "42", "Song", "Artist")))).payload()));
        assertEquals(ProtocolGoldenVectors.CHUNK_REQUEST, hex(LegacyEnvelope.encode(
                LegacyPacketTypes.CHUNK_REQUEST,
                new TransportPackets.ChunkRequest(
                        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 300, 17, 8)).payload()));
    }

    @Test
    void rejectsWrongDirectionUnknownIdsAndTrailingBytes() {
        LegacyEnvelope hello = LegacyEnvelope.encode(LegacyPacketTypes.CLIENT_HELLO,
                new ControlPackets.ClientHello(ProtocolLimits.VERSION));
        assertThrows(ProtocolException.class, () -> hello.decode(LegacyPacketTypes.Direction.CLIENTBOUND));

        byte[] unknown = ints(127, 0);
        assertThrows(ProtocolException.class, () -> LegacyEnvelope.read(unknown));

        byte[] validPayload = hello.payload();
        byte[] trailing = new byte[validPayload.length + 9];
        java.nio.ByteBuffer.wrap(trailing).putInt(hello.messageId()).putInt(validPayload.length)
                .put(validPayload).put((byte) 99);
        assertThrows(ProtocolException.class, () -> LegacyEnvelope.read(trailing));
    }

    @Test
    void rejectsOversizedDeclaredPayload() {
        byte[] oversized = ints(LegacyPacketTypes.AUDIO_CHUNK.id(), LegacyEnvelope.MAX_ENVELOPE_BYTES + 1);
        assertThrows(ProtocolException.class, () -> LegacyEnvelope.read(oversized));
    }

    private static byte[] ints(int first, int second) {
        return java.nio.ByteBuffer.allocate(8).putInt(first).putInt(second).array();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
