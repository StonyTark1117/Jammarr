package stonytark.jammarr.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JammarrNetworkTest {
    @Test
    void acceptsOnlyTheCurrentProtocol() {
        assertTrue(JammarrNetwork.protocolMatches(JammarrNetwork.PROTOCOL));
        assertFalse(JammarrNetwork.protocolMatches(JammarrNetwork.PROTOCOL - 1));
        assertFalse(JammarrNetwork.protocolMatches(JammarrNetwork.PROTOCOL + 1));
    }
}
