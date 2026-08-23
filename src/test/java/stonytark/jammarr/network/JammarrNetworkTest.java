package stonytark.jammarr.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JammarrNetworkTest {
    @Test
    void acceptsOnlyTheCurrentProtocol() {
        assertTrue(JammarrNetwork.protocolMatches(JammarrNetwork.PROTOCOL));
        assertFalse(JammarrNetwork.protocolMatches(JammarrNetwork.PROTOCOL - 1));
        assertFalse(JammarrNetwork.protocolMatches(JammarrNetwork.PROTOCOL + 1));
    }

    @Test void stationRequestCodecRoundTripsAndBoundsSeeds() {
        List<JammarrPayloads.StationSeed> seeds = IntStream.range(0, 6).mapToObj(i ->
                new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, Integer.toString(i), "Track " + i, "Artist")).toList();
        JammarrPayloads.StationRequest decoded = roundTrip(JammarrPayloads.StationRequest.CODEC,
                new JammarrPayloads.StationRequest(JammarrPayloads.StationAction.START_NOW, JammarrPayloads.StationType.SONIC_ADVENTURE, false, 12, seeds));
        assertEquals(JammarrPayloads.StationType.SONIC_ADVENTURE, decoded.stationType()); assertEquals(12, decoded.expectedGeneration());
        assertEquals(5, decoded.seeds().size());
    }

    @Test void stationStateCodecPreservesCapabilitySourceAndPreview() {
        var preview = new JammarrPayloads.QueueEntry("1", "Track", "Artist", 1000, JammarrPayloads.PlaybackOrigin.ADVENTURE, false);
        var state = new JammarrPayloads.StationState(JammarrPayloads.StationType.SONIC_ADVENTURE, true, false, 3,
                JammarrPayloads.SonicCapability.ANALYSIS_INCOMPLETE, "Seed is missing analysis", "Adventure",
                List.of(new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, "1", "Track", "Artist")), List.of(preview));
        assertEquals(state, roundTrip(JammarrPayloads.StationState.CODEC, state));
    }

    @Test void playbackStateCodecPreservesTheCurrentNamedSource() {
        var state = new JammarrPayloads.PlaybackState(JammarrPayloads.PlaybackStatus.PLAYING, "", "Track", "Artist",
                false, 1_000, 2_000, 3_000, true, JammarrPayloads.PlaybackOrigin.STATION, "Artist Radio: Seed", List.of());
        assertEquals(state, roundTrip(JammarrPayloads.PlaybackState.CODEC, state));
    }

    @Test void stationRequestDecoderRejectsOversizedSeedLists() {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeEnum(JammarrPayloads.StationAction.START);
        buffer.writeEnum(JammarrPayloads.StationType.SONIC_MIX);
        buffer.writeBoolean(false); buffer.writeVarLong(1); buffer.writeVarInt(6);
        buffer.readerIndex(0);
        assertThrows(io.netty.handler.codec.DecoderException.class, () -> JammarrPayloads.StationRequest.CODEC.decode(buffer));
    }

    private static <T> T roundTrip(net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buffer = buffer();
        codec.encode(buffer, value); buffer.readerIndex(0); return codec.decode(buffer);
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
