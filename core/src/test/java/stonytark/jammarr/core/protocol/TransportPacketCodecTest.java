package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportPacketCodecTest {
    private static final UUID SESSION = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test void chunkRequestMatchesTheProtocolFiveGoldenVector() {
        TransportPackets.ChunkRequest value = new TransportPackets.ChunkRequest(SESSION, 300, 17, 8);
        byte[] encoded = encode(TransportPackets.CHUNK_REQUEST, value);
        assertEquals(ProtocolGoldenVectors.CHUNK_REQUEST, hex(encoded));
        TransportPackets.ChunkRequest decoded = TransportPackets.CHUNK_REQUEST.decode(new ByteArrayWireInput(encoded));
        assertEquals(SESSION, decoded.sessionId()); assertEquals(300, decoded.requestId());
        assertEquals(17, decoded.startIndex()); assertEquals(8, decoded.count());
    }

    @Test void acknowledgementMatchesTheProtocolFiveGoldenVector() {
        TransportPackets.ChunkAcknowledgement value = new TransportPackets.ChunkAcknowledgement(SESSION, 300, 24, 12_000);
        byte[] encoded = encode(TransportPackets.CHUNK_ACKNOWLEDGEMENT, value);
        assertEquals("00112233445566778899aabbccddeeffac0218e05d", hex(encoded));
        TransportPackets.ChunkAcknowledgement decoded = TransportPackets.CHUNK_ACKNOWLEDGEMENT.decode(new ByteArrayWireInput(encoded));
        assertEquals(24, decoded.receivedThroughIndex()); assertEquals(12_000, decoded.bufferedMs());
    }

    @Test void audioChunkRejectsAnOversizedDeclaredBodyBeforeAllocation() {
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        output.writeUuid(SESSION); output.writeVarLong(1); output.writeVarInt(0); output.writeVarLong(0);
        output.writeUtf("0000000000000000000000000000000000000000000000000000000000000000", 64);
        output.writeVarInt(ProtocolLimits.MAX_AUDIO_CHUNK_BYTES + 1);
        assertThrows(ProtocolException.class, () -> TransportPackets.AUDIO_CHUNK.decode(new ByteArrayWireInput(output.toByteArray())));
    }

    @Test void malformedVarIntIsRejected() {
        assertThrows(ProtocolException.class, () -> new ByteArrayWireInput(new byte[]{-128, -128, -128, -128, -128, 0}).readVarInt());
    }

    @Test void signedMaximumWidthVarLongRoundTrips() {
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        output.writeVarLong(-1L);
        assertEquals(-1L, new ByteArrayWireInput(output.toByteArray()).readVarLong());
    }

    @Test void truncatedDeclaredFieldLengthIsRejected() {
        assertThrows(ProtocolException.class,
                () -> new ByteArrayWireInput(new byte[] { 5, 'a' }).readUtf(16));
        assertThrows(ProtocolException.class,
                () -> new ByteArrayWireInput(new byte[] { 4, 1, 2 }).readByteArray(16));
    }

    @Test void audioChunkRoundTripsBinaryData() {
        byte[] data = new byte[]{0, 1, 2, -1};
        TransportPackets.AudioChunk value = new TransportPackets.AudioChunk(SESSION, 2, 3, 4_000,
                "0000000000000000000000000000000000000000000000000000000000000000", data);
        TransportPackets.AudioChunk decoded = TransportPackets.AUDIO_CHUNK.decode(new ByteArrayWireInput(encode(TransportPackets.AUDIO_CHUNK, value)));
        assertEquals(SESSION, decoded.sessionId()); assertEquals(4_000, decoded.startMs()); assertArrayEquals(data, decoded.data());
    }

    private static <T> byte[] encode(WireCodec<T> codec, T value) {
        ByteArrayWireOutput output = new ByteArrayWireOutput(); codec.encode(output, value); return output.toByteArray();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
