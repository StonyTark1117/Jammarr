package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.StatePackets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorPolicyTest {
    @Test void manualRequestsAlwaysBeatGeneratedLookahead() {
        List<QueueTrack> manual = new ArrayList<QueueTrack>(Collections.singletonList(track("manual")));
        ArrayDeque<QueueTrack> generated = new ArrayDeque<QueueTrack>(Collections.singletonList(track("station")));
        PlaybackSourcePolicy.Selection first = PlaybackSourcePolicy.takeNext(manual, generated, false);
        assertEquals("manual", first.track().key());
        assertEquals(StatePackets.PlaybackOrigin.MANUAL, first.origin());
        PlaybackSourcePolicy.Selection second = PlaybackSourcePolicy.takeNext(manual, generated, false);
        assertEquals("station", second.track().key());
        assertEquals(StatePackets.PlaybackOrigin.STATION, second.origin());
    }

    @Test void adventureGeneratedTracksHaveTheirOwnOrigin() {
        ArrayDeque<QueueTrack> generated = new ArrayDeque<QueueTrack>(Collections.singletonList(track("path")));
        assertEquals(StatePackets.PlaybackOrigin.ADVENTURE,
                PlaybackSourcePolicy.takeNext(new ArrayList<QueueTrack>(), generated, true).origin());
    }

    @Test void generatedPreviewAndCurrentRowsCannotBeMoved() {
        List<StatePackets.QueueEntry> visible = Arrays.asList(
                entry("current", StatePackets.PlaybackOrigin.STATION, false),
                entry("manual-1", StatePackets.PlaybackOrigin.MANUAL, true),
                entry("manual-2", StatePackets.PlaybackOrigin.MANUAL, true),
                entry("preview", StatePackets.PlaybackOrigin.STATION, false));
        assertFalse(PlaybackSourcePolicy.canMove(visible, 1, -1));
        assertTrue(PlaybackSourcePolicy.canMove(visible, 1, 1));
        assertFalse(PlaybackSourcePolicy.canMove(visible, 2, 1));
    }

    @Test void stationControlsRequireOperatorAndCurrentGeneration() {
        assertEquals(StationControlPolicy.Decision.PERMISSION_DENIED, StationControlPolicy.assess(false, 4, 4));
        assertEquals(StationControlPolicy.Decision.STALE_GENERATION, StationControlPolicy.assess(true, 3, 4));
        assertEquals(StationControlPolicy.Decision.ALLOW, StationControlPolicy.assess(true, 4, 4));
    }

    @Test void onlyConfirmedStartNowReplacesCurrentPlayback() {
        assertFalse(StationControlPolicy.replacesCurrentPlayback(ControlPackets.StationAction.START));
        assertFalse(StationControlPolicy.replacesCurrentPlayback(ControlPackets.StationAction.STOP));
        assertTrue(StationControlPolicy.replacesCurrentPlayback(ControlPackets.StationAction.START_NOW));
    }

    private static StatePackets.QueueEntry entry(String key, StatePackets.PlaybackOrigin origin, boolean editable) {
        return new StatePackets.QueueEntry(key, key, "Artist", 1_000L, origin, editable);
    }

    private static QueueTrack track(String key) { return new QueueTrack(key, key, "Artist", "Album", 1_000L); }
}
