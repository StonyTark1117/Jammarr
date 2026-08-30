package stonytark.jammarr.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.protocol.ProtocolGoldenVectors;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JammarrNeoForge1202NetworkTest {
    @Test void acceptsOnlyProtocolSix() {
        assertEquals(6, JammarrNetwork.PROTOCOL);
        assertTrue(JammarrNetwork.protocolMatches(6));
        assertFalse(JammarrNetwork.protocolMatches(4));
        assertFalse(JammarrNetwork.protocolMatches(7));
    }

    @Test void stationCodecMatchesTheSharedGoldenVector() {
        FriendlyByteBuf buffer = buffer();
        JammarrPayloads.StationRequest value = new JammarrPayloads.StationRequest(
                JammarrPayloads.StationAction.START_NOW, JammarrPayloads.StationType.SONIC_ADVENTURE,
                false, 12, List.of(new JammarrPayloads.StationSeed(
                JammarrPayloads.ItemKind.TRACK, "42", "Song", "Artist")));
        value.write(buffer);
        assertEquals(ProtocolGoldenVectors.STATION_REQUEST, hex(buffer));
        buffer.readerIndex(0);
        assertEquals(value, JammarrPayloads.StationRequest.read(buffer));
    }

    @Test void browseCodecMatchesTheSharedGoldenVector() {
        FriendlyByteBuf buffer = buffer();
        JammarrPayloads.BrowseRequest value = new JammarrPayloads.BrowseRequest(
                JammarrPayloads.BrowseKind.SEARCH, "A&B", 2);
        value.write(buffer);
        assertEquals(ProtocolGoldenVectors.BROWSE_REQUEST, hex(buffer));
        buffer.readerIndex(0);
        assertEquals(value, JammarrPayloads.BrowseRequest.read(buffer));
    }

    @Test void malformedOversizedStationListIsRejected() {
        FriendlyByteBuf buffer = buffer();
        buffer.writeVarInt(JammarrPayloads.StationAction.START.ordinal());
        buffer.writeVarInt(JammarrPayloads.StationType.SONIC_MIX.ordinal());
        buffer.writeBoolean(false);
        buffer.writeVarLong(1);
        buffer.writeVarInt(6);
        buffer.readerIndex(0);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> JammarrPayloads.StationRequest.read(buffer));
    }

    private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }

    private static String hex(FriendlyByteBuf buffer) {
        byte[] value = new byte[buffer.readableBytes()];
        buffer.getBytes(0, value);
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
