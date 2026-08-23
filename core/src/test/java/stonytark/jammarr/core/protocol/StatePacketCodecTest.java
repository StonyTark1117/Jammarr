package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatePacketCodecTest {
    @Test void queueEntryMatchesProtocolFiveGoldenVector() {
        StatePackets.QueueEntry entry = new StatePackets.QueueEntry("1", "T", "A", 1_000,
                StatePackets.PlaybackOrigin.ADVENTURE, false);
        byte[] bytes = encode(StatePackets.QUEUE_ENTRY, entry);
        assertEquals("013101540141e8070300", hex(bytes));
        StatePackets.QueueEntry decoded = StatePackets.QUEUE_ENTRY.decode(new ByteArrayWireInput(bytes));
        assertEquals(StatePackets.PlaybackOrigin.ADVENTURE, decoded.source());
        assertEquals(1_000, decoded.durationMs());
    }

    @Test void errorMessageMatchesProtocolFiveGoldenVector() {
        assertEquals("0204736c6f77", hex(encode(StatePackets.ERROR_MESSAGE,
                new StatePackets.ErrorMessage(StatePackets.ErrorCode.RATE_LIMITED, "slow"))));
    }

    @Test void rejectsInvalidPlaybackStatus() {
        assertThrows(ProtocolException.class, () -> StatePackets.PLAYBACK_STATE.decode(
                new ByteArrayWireInput(new byte[] { 0x7f })));
    }

    @Test void rejectsOversizedPlaybackQueue() {
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        output.writeVarInt(StatePackets.PlaybackStatus.IDLE.ordinal());
        output.writeUtf("", 256);
        output.writeUtf("", 256);
        output.writeUtf("", 256);
        output.writeBoolean(false);
        output.writeVarLong(0);
        output.writeVarLong(0);
        output.writeLong(0);
        output.writeBoolean(false);
        output.writeVarInt(StatePackets.PlaybackOrigin.NONE.ordinal());
        output.writeUtf("", 256);
        output.writeVarInt(ProtocolLimits.MAX_PLAYBACK_ENTRIES + 1);
        assertThrows(ProtocolException.class, () -> StatePackets.PLAYBACK_STATE.decode(
                new ByteArrayWireInput(output.toByteArray())));
    }

    private static <T> byte[] encode(WireCodec<T> codec, T value) {
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        codec.encode(output, value);
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
