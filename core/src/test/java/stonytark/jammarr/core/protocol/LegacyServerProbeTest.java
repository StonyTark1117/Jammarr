package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyServerProbeTest {
    private static final String NONCE = "00112233445566778899aabbccddeeff";

    @Test void roundTripsAHiddenProtocolResponse() {
        assertEquals("/jammarr handshake 6 " + NONCE, LegacyServerProbe.command(6, NONCE));
        assertEquals(6, LegacyServerProbe.responseProtocol(LegacyServerProbe.response(6, NONCE), NONCE));
    }

    @Test void bindsTheResponseToTheConnectionNonce() {
        assertEquals(-1, LegacyServerProbe.responseProtocol(
                LegacyServerProbe.response(6, "ffeeddccbbaa99887766554433221100"), NONCE));
        assertEquals(-1, LegacyServerProbe.responseProtocol("ordinary chat", NONCE));
    }

    @Test void recognizesTheVanillaUnknownCommandReplyWithoutItsColor() {
        assertTrue(LegacyServerProbe.unknownCommand("\u00a77Unknown command. Type \"help\" for help."));
    }

    @Test void rejectsMalformedProbeInputs() {
        assertThrows(IllegalArgumentException.class, () -> LegacyServerProbe.command(6, "short"));
        assertThrows(IllegalArgumentException.class, () -> LegacyServerProbe.response(0, NONCE));
    }
}
