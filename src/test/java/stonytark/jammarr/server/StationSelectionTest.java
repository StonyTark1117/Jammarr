package stonytark.jammarr.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StationSelectionTest {
    @Test void reciprocalRankFusionRewardsTracksSharedBySeveralSeeds() {
        QueueTrack a = track("a", "Artist A"), b = track("b", "Artist B"), shared = track("shared", "Shared");
        List<QueueTrack> result = StationSelection.reciprocalRankFusion(List.of(List.of(a, shared), List.of(b, shared)), Set.of(), Set.of(), 10);
        assertEquals("shared", result.getFirst().key());
    }

    @Test void fusionExcludesRecentTracksAndArtists() {
        List<QueueTrack> result = StationSelection.reciprocalRankFusion(List.of(List.of(track("1", "Recent"), track("2", "Fresh"))),
                Set.of(), Set.of("Recent"), 10);
        assertEquals(List.of("2"), result.stream().map(QueueTrack::key).toList());
    }

    @Test void adventureSegmentsDeduplicateTheirSharedWaypoints() {
        assertEquals(List.of("1", "2", "3"), StationSelection.deduplicatePath(
                List.of(List.of(track("1", "A"), track("2", "B")), List.of(track("2", "B"), track("3", "C"))), 100)
                .stream().map(QueueTrack::key).toList());
    }

    @Test void validatesModeSpecificSeedRules() throws Exception {
        assertDoesNotThrow(() -> StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure",
                List.of(seed(JammarrPayloads.ItemKind.TRACK, "1"), seed(JammarrPayloads.ItemKind.TRACK, "2")), 1)));
        assertThrows(PlexException.class, () -> StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_MIX, "Mix",
                List.of(seed(JammarrPayloads.ItemKind.TRACK, "1"), seed(JammarrPayloads.ItemKind.ARTIST, "2")), 1)));
        assertThrows(PlexException.class, () -> StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure",
                List.of(seed(JammarrPayloads.ItemKind.TRACK, "1")), 1)));
    }

    private static QueueTrack track(String key, String artist) { return new QueueTrack(key, key, artist, "Album", 1_000); }
    private static JammarrPayloads.StationSeed seed(JammarrPayloads.ItemKind kind, String key) { return new JammarrPayloads.StationSeed(kind, key, key, ""); }
}
