package stonytark.jammarr.server;

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
                track("current").networkEntry(JammarrPayloads.PlaybackOrigin.STATION, false),
                track("manual-1").networkEntry(JammarrPayloads.PlaybackOrigin.MANUAL, true),
                track("manual-2").networkEntry(JammarrPayloads.PlaybackOrigin.MANUAL, true),
                track("preview").networkEntry(JammarrPayloads.PlaybackOrigin.STATION, false));
        assertFalse(PlaybackSourcePolicy.canMove(visible, 1, -1)); assertTrue(PlaybackSourcePolicy.canMove(visible, 1, 1));
        assertFalse(PlaybackSourcePolicy.canMove(visible, 2, 1));
    }

    private static QueueTrack track(String key) { return new QueueTrack(key, key, "Artist", "Album", 1_000); }
}
