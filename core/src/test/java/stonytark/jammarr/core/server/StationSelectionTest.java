package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.QueueTrack;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StationSelectionTest {
    @Test void reciprocalRankFusionRewardsTracksSharedBySeveralSeeds() {
        QueueTrack a = track("a", "Artist A"), b = track("b", "Artist B"), shared = track("shared", "Shared");
        List<QueueTrack> result = StationSelection.reciprocalRankFusion(Arrays.asList(Arrays.asList(a, shared), Arrays.asList(b, shared)),
                Collections.<String>emptySet(), Collections.<String>emptySet(), 10);
        assertEquals("shared", result.get(0).key());
    }

    @Test void fusionExcludesRecentTracksAndArtists() {
        List<QueueTrack> result = StationSelection.reciprocalRankFusion(Arrays.asList(Arrays.asList(track("1", "Recent"), track("2", "Fresh"))),
                Collections.<String>emptySet(), Collections.singleton("Recent"), 10);
        assertEquals(Arrays.asList("2"), keys(result));
    }

    @Test void adventureSegmentsDeduplicateTheirSharedWaypoints() {
        assertEquals(Arrays.asList("1", "2", "3"), keys(StationSelection.deduplicatePath(
                Arrays.asList(Arrays.asList(track("1", "A"), track("2", "B")), Arrays.asList(track("2", "B"), track("3", "C"))), 100)));
    }

    private static List<String> keys(List<QueueTrack> tracks) {
        List<String> keys = new java.util.ArrayList<String>();
        for (QueueTrack track : tracks) keys.add(track.key());
        return keys;
    }
    private static QueueTrack track(String key, String artist) { return new QueueTrack(key, key, artist, "Album", 1_000); }
}
