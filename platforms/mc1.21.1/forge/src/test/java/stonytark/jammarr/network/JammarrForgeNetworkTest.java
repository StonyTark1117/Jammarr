package stonytark.jammarr.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.protocol.ProtocolGoldenVectors;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JammarrForgeNetworkTest {
    @Test void acceptsOnlyProtocolSix() {
        assertEquals(6, JammarrNetwork.PROTOCOL);
        assertTrue(JammarrNetwork.protocolMatches(6));
        assertFalse(JammarrNetwork.protocolMatches(4));
        assertFalse(JammarrNetwork.protocolMatches(7));
    }

    @Test void stationCodecMatchesTheSharedGoldenVector() {
        RegistryFriendlyByteBuf buffer = buffer();
        JammarrPayloads.StationRequest.CODEC.encode(buffer, new JammarrPayloads.StationRequest(
                JammarrPayloads.StationAction.START_NOW, JammarrPayloads.StationType.SONIC_ADVENTURE,
                false, 12, List.of(new JammarrPayloads.StationSeed(
                        JammarrPayloads.ItemKind.TRACK, "42", "Song", "Artist"))));
        byte[] encoded = new byte[buffer.readableBytes()];
        buffer.getBytes(0, encoded);
        assertEquals(ProtocolGoldenVectors.STATION_REQUEST, hex(encoded));
    }

    @Test void browseCodecMatchesTheSharedGoldenVector() {
        RegistryFriendlyByteBuf buffer = buffer();
        JammarrPayloads.BrowseRequest.CODEC.encode(buffer,
                new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "A&B", 2));
        byte[] encoded = new byte[buffer.readableBytes()];
        buffer.getBytes(0, encoded);
        assertEquals(ProtocolGoldenVectors.BROWSE_REQUEST, hex(encoded));
    }

    @Test void malformedOversizedStationListIsRejected() {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeEnum(JammarrPayloads.StationAction.START);
        buffer.writeEnum(JammarrPayloads.StationType.SONIC_MIX);
        buffer.writeBoolean(false);
        buffer.writeVarLong(1);
        buffer.writeVarInt(6);
        buffer.readerIndex(0);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> JammarrPayloads.StationRequest.CODEC.decode(buffer));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
