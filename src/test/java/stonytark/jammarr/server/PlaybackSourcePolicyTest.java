package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;


import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackSourcePolicyTest {
    @Test void manualRequestsAlwaysBeatGeneratedLookahead() {
        var manual = new ArrayList<>(List.of(track("manual"))); var generated = new ArrayDeque<>(List.of(track("station")));
        PlaybackSourcePolicy.Selection first = PlaybackSourcePolicy.takeNext(manual, generated, false);
        assertEquals("manual", first.track().key()); assertEquals(JammarrPayloads.PlaybackOrigin.MANUAL, first.origin());
        PlaybackSourcePolicy.Selection second = PlaybackSourcePolicy.takeNext(manual, generated, false);
        assertEquals("station", second.track().key()); assertEquals(JammarrPayloads.PlaybackOrigin.STATION, second.origin());
    }

    @Test void adventureGeneratedTracksHaveTheirOwnOrigin() {
        var generated = new ArrayDeque<>(List.of(track("path")));
        assertEquals(JammarrPayloads.PlaybackOrigin.ADVENTURE, PlaybackSourcePolicy.takeNext(new ArrayList<>(), generated, true).origin());
    }

    @Test void generatedPreviewAndCurrentRowsCannotBeMoved() {
        List<JammarrPayloads.QueueEntry> visible = List.of(
                QueueTrackCodec.networkEntry(track("current"), JammarrPayloads.PlaybackOrigin.STATION, false),
                QueueTrackCodec.networkEntry(track("manual-1"), JammarrPayloads.PlaybackOrigin.MANUAL, true),
                QueueTrackCodec.networkEntry(track("manual-2"), JammarrPayloads.PlaybackOrigin.MANUAL, true),
                QueueTrackCodec.networkEntry(track("preview"), JammarrPayloads.PlaybackOrigin.STATION, false));
        assertFalse(PlaybackSourcePolicy.canMove(visible, 1, -1)); assertTrue(PlaybackSourcePolicy.canMove(visible, 1, 1));
        assertFalse(PlaybackSourcePolicy.canMove(visible, 2, 1));
    }

    private static QueueTrack track(String key) { return new QueueTrack(key, key, "Artist", "Album", 1_000); }
}
