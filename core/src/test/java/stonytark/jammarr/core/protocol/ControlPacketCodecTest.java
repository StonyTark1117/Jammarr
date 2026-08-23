package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.model.StationModels.StationType;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlPacketCodecTest {
    @Test void protocolFiveStationRequestMatchesGoldenVector() {
        ControlPackets.StationRequest request = new ControlPackets.StationRequest(
                ControlPackets.StationAction.START_NOW, StationType.SONIC_ADVENTURE, false, 12,
                Collections.singletonList(new StationSeed(ItemKind.TRACK, "42", "Song", "Artist")));
        byte[] bytes = encode(ControlPackets.STATION_REQUEST, request);
        assertEquals("0107000c010002343204536f6e6706417274697374", hex(bytes));
        ControlPackets.StationRequest decoded = ControlPackets.STATION_REQUEST.decode(new ByteArrayWireInput(bytes));
        assertEquals(ControlPackets.StationAction.START_NOW, decoded.action());
        assertEquals(StationType.SONIC_ADVENTURE, decoded.stationType());
        assertEquals("42", decoded.seeds().get(0).key());
    }

    @Test void browseRequestMatchesGoldenVector() {
        byte[] bytes = encode(ControlPackets.BROWSE_REQUEST,
                new ControlPackets.BrowseRequest(ControlPackets.BrowseKind.SEARCH, "A&B", 2));
        assertEquals("000341264202", hex(bytes));
    }

    @Test void rejectsInvalidEnumOrdinal() {
        assertThrows(ProtocolException.class, () -> ControlPackets.BROWSE_REQUEST.decode(
                new ByteArrayWireInput(new byte[] { 0x7f, 0, 0 })));
    }

    @Test void rejectsOversizedStationSeedList() {
        ByteArrayWireOutput output = new ByteArrayWireOutput();
        output.writeVarInt(ControlPackets.StationAction.START.ordinal());
        output.writeVarInt(StationType.TRACK_RADIO.ordinal());
        output.writeBoolean(false);
        output.writeVarLong(1);
        output.writeVarInt(ProtocolLimits.MAX_STATION_SEEDS + 1);
        assertThrows(ProtocolException.class, () -> ControlPackets.STATION_REQUEST.decode(
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
