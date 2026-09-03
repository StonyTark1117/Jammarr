package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.MediaItem;
import stonytark.jammarr.core.model.StationModels.PlaylistAvailability;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.model.StationModels.StationType;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlPacketCodecTest {
    @Test void protocolSixBrowseCarriesPlaylistAvailabilityAndOutcome() {
        ControlPackets.BrowseResults value = new ControlPackets.BrowseResults(
                ControlPackets.BrowseKind.PLAYLISTS, "", 0, false,
                Collections.singletonList(new MediaItem(ItemKind.PLAYLIST, "p", "Empty", "[Unavailable: empty playlist]", 0,
                        PlaylistAvailability.EMPTY)), ControlPackets.BrowseOutcome.ALL_PLAYLISTS_FILTERED,
                "Playlists returned by Plex, but all are unavailable for this library");
        ControlPackets.BrowseResults decoded = ControlPackets.BROWSE_RESULTS.decode(
                new ByteArrayWireInput(encode(ControlPackets.BROWSE_RESULTS, value)));
        assertEquals(PlaylistAvailability.EMPTY, decoded.items().get(0).availability());
        assertEquals(ControlPackets.BrowseOutcome.ALL_PLAYLISTS_FILTERED, decoded.outcome());
        assertEquals(value.message(), decoded.message());
    }
    @Test void protocolSixHelloAdvertisesFeaturesAndTransportLimits() {
        ControlPackets.ClientHello value = new ControlPackets.ClientHello(ProtocolLimits.VERSION);
        byte[] bytes = encode(ControlPackets.CLIENT_HELLO, value);
        assertEquals(ProtocolGoldenVectors.CLIENT_HELLO, hex(bytes));
        ControlPackets.ClientHello decoded = ControlPackets.CLIENT_HELLO.decode(new ByteArrayWireInput(bytes));
        assertEquals(ProtocolCapabilities.SUPPORTED_FEATURES, decoded.features());
        assertEquals(ProtocolCapabilities.AUDIO_CHUNK_BYTES, decoded.audioChunkBytes());
        assertEquals(ProtocolCapabilities.CHUNKS_PER_REQUEST, decoded.chunksPerRequest());
    }

    @Test void nonIndexedControlRoundTripsItsNegativeSentinel() {
        ControlPackets.ControlRequest value = new ControlPackets.ControlRequest(
                ControlPackets.ControlAction.PAUSE, -1, "");
        ControlPackets.ControlRequest decoded = ControlPackets.CONTROL_REQUEST.decode(
                new ByteArrayWireInput(encode(ControlPackets.CONTROL_REQUEST, value)));
        assertEquals(ControlPackets.ControlAction.PAUSE, decoded.action());
        assertEquals(-1, decoded.index());
        assertEquals("", decoded.expectedKey());
    }

    @Test void protocolSixStationRequestMatchesGoldenVector() {
        ControlPackets.StationRequest request = new ControlPackets.StationRequest(
                ControlPackets.StationAction.START_NOW, StationType.SONIC_ADVENTURE, false, 12,
                Collections.singletonList(new StationSeed(ItemKind.TRACK, "42", "Song", "Artist")));
        byte[] bytes = encode(ControlPackets.STATION_REQUEST, request);
        assertEquals(ProtocolGoldenVectors.STATION_REQUEST, hex(bytes));
        ControlPackets.StationRequest decoded = ControlPackets.STATION_REQUEST.decode(new ByteArrayWireInput(bytes));
        assertEquals(ControlPackets.StationAction.START_NOW, decoded.action());
        assertEquals(StationType.SONIC_ADVENTURE, decoded.stationType());
        assertEquals("42", decoded.seeds().get(0).key());
    }

    @Test void browseRequestMatchesGoldenVector() {
        byte[] bytes = encode(ControlPackets.BROWSE_REQUEST,
                new ControlPackets.BrowseRequest(ControlPackets.BrowseKind.SEARCH, "A&B", 2));
        assertEquals(ProtocolGoldenVectors.BROWSE_REQUEST, hex(bytes));
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
