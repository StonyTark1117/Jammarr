package stonytark.jammarr.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;

import static org.junit.jupiter.api.Assertions.*;

class StationControlPolicyTest {
    @Test void stationControlsRequireOperatorAndCurrentGeneration() {
        assertEquals(StationControlPolicy.Decision.PERMISSION_DENIED, StationControlPolicy.assess(false, 4, 4));
        assertEquals(StationControlPolicy.Decision.STALE_GENERATION, StationControlPolicy.assess(true, 3, 4));
        assertEquals(StationControlPolicy.Decision.ALLOW, StationControlPolicy.assess(true, 4, 4));
    }

    @Test void onlyConfirmedStartNowReplacesCurrentAndManualPlayback() {
        assertFalse(StationControlPolicy.replacesCurrentPlayback(JammarrPayloads.StationAction.START));
        assertFalse(StationControlPolicy.replacesCurrentPlayback(JammarrPayloads.StationAction.STOP));
        assertTrue(StationControlPolicy.replacesCurrentPlayback(JammarrPayloads.StationAction.START_NOW));
    }
}
