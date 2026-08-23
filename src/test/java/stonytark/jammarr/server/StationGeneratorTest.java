package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;


import stonytark.jammarr.core.server.PlexException;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class StationGeneratorTest {
    @Test void prefersAdvertisedNativeRadioBeforeSonicNearest() throws Exception {
        FakeCatalog catalog = new FakeCatalog(); catalog.nativeTracks = List.of(track("native", "New Artist"));
        var result = new StationGenerator(catalog).generate(station(JammarrPayloads.StationType.ARTIST_RADIO,
                seed(JammarrPayloads.ItemKind.ARTIST, "artist")), List.of(), JammarrPayloads.SonicCapability.READY, false);
        assertEquals(List.of("native"), keys(result.tracks()));
        assertEquals(1, catalog.nativeCalls); assertEquals(0, catalog.nearestCalls);
    }

    @Test void widensTrackDistanceOnceAndSuppressesRecentTracksAndArtists() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.nearestTracks.put(0.25, List.of());
        catalog.nearestTracks.put(0.40, List.of(track("old-key", "Fresh"), track("same-artist", "Old Artist"), track("chosen", "Fresh")));
        List<QueueTrack> history = List.of(track("old-key", "Earlier"), track("prior", "Old Artist"));
        var result = new StationGenerator(catalog).generate(station(JammarrPayloads.StationType.TRACK_RADIO,
                seed(JammarrPayloads.ItemKind.TRACK, "seed")), history, JammarrPayloads.SonicCapability.READY, false);
        assertEquals(List.of("chosen"), keys(result.tracks()));
        assertEquals(List.of(0.25, 0.40), catalog.trackDistances);
    }

    @Test void gatesMetadataFallbackAndNeverUsesItForAdventure() throws Exception {
        FakeCatalog catalog = new FakeCatalog(); catalog.fallbackTracks = List.of(track("fallback", "Artist"));
        StationGenerator generator = new StationGenerator(catalog);
        StationDefinition radio = station(JammarrPayloads.StationType.TRACK_RADIO, seed(JammarrPayloads.ItemKind.TRACK, "seed"));
        assertThrows(PlexException.class, () -> generator.generate(radio, List.of(), JammarrPayloads.SonicCapability.NO_PLEX_PASS, false));
        assertEquals(List.of("fallback"), keys(generator.generate(radio, List.of(), JammarrPayloads.SonicCapability.NO_PLEX_PASS, true).tracks()));
        StationDefinition adventure = station(JammarrPayloads.StationType.SONIC_ADVENTURE,
                seed(JammarrPayloads.ItemKind.TRACK, "one"), seed(JammarrPayloads.ItemKind.TRACK, "two"));
        assertThrows(PlexException.class, () -> generator.generate(adventure, List.of(), JammarrPayloads.SonicCapability.NO_PLEX_PASS, true));
        assertEquals(1, catalog.fallbackCalls);
    }

    @Test void adventurePreservesWaypointOrderDeduplicatesJoinsAndRejectsIncompletePaths() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.paths.put("one->two", List.of(track("one", "A"), track("middle", "B"), track("two", "C")));
        catalog.paths.put("two->three", List.of(track("two", "C"), track("three", "D")));
        StationGenerator generator = new StationGenerator(catalog);
        StationDefinition adventure = station(JammarrPayloads.StationType.SONIC_ADVENTURE,
                seed(JammarrPayloads.ItemKind.TRACK, "one"), seed(JammarrPayloads.ItemKind.TRACK, "two"), seed(JammarrPayloads.ItemKind.TRACK, "three"));
        assertEquals(List.of("one", "middle", "two", "three"), keys(generator.generate(
                adventure, List.of(), JammarrPayloads.SonicCapability.READY, false).tracks()));
        catalog.paths.put("two->three", List.of(track("two", "C")));
        PlexException partial = assertThrows(PlexException.class, () -> generator.generate(
                adventure, List.of(), JammarrPayloads.SonicCapability.READY, false));
        assertTrue(partial.getMessage().contains("waypoint 2 and 3"));
    }

    @Test void adventureRejectsAnUnanalyzedWaypointBeforeComputingPaths() {
        FakeCatalog catalog = new FakeCatalog(); catalog.unanalyzed.add("two");
        StationDefinition adventure = station(JammarrPayloads.StationType.SONIC_ADVENTURE,
                seed(JammarrPayloads.ItemKind.TRACK, "one"), seed(JammarrPayloads.ItemKind.TRACK, "two"));
        PlexException missing = assertThrows(PlexException.class, () -> new StationGenerator(catalog).generate(
                adventure, List.of(), JammarrPayloads.SonicCapability.READY, false));
        assertTrue(missing.getMessage().contains("waypoint 2")); assertEquals(0, catalog.pathCalls);
    }

    @Test void libraryShuffleAvoidsRecentArtistsBeforeRelaxingThatRule() throws Exception {
        FakeCatalog catalog = new FakeCatalog(); catalog.randomTracks = List.of(track("one", "Recent Artist"), track("two", "New Artist"));
        var result = new StationGenerator(catalog).generate(station(JammarrPayloads.StationType.LIBRARY_SHUFFLE),
                List.of(track("history", "Recent Artist")), JammarrPayloads.SonicCapability.ANALYSIS_INCOMPLETE, false);
        assertEquals(List.of("two"), keys(result.tracks()));
    }

    private static StationDefinition station(JammarrPayloads.StationType type, JammarrPayloads.StationSeed... seeds) {
        return new StationDefinition(type, type.name(), List.of(seeds), 1);
    }
    private static JammarrPayloads.StationSeed seed(JammarrPayloads.ItemKind kind, String key) {
        return new JammarrPayloads.StationSeed(kind, key, key, "Artist");
    }
    private static QueueTrack track(String key, String artist) { return new QueueTrack(key, key, artist, "Album", 1_000); }
    private static List<String> keys(List<QueueTrack> tracks) { return tracks.stream().map(QueueTrack::key).toList(); }

    private static final class FakeCatalog implements StationCatalog {
        private List<QueueTrack> nativeTracks = List.of(), fallbackTracks = List.of(), randomTracks = List.of();
        private final Map<Double, List<QueueTrack>> nearestTracks = new HashMap<>();
        private final Map<String, List<QueueTrack>> paths = new HashMap<>();
        private final Set<String> unanalyzed = new java.util.HashSet<>();
        private final List<Double> trackDistances = new ArrayList<>();
        private int nativeCalls, nearestCalls, fallbackCalls, pathCalls;

        @Override public List<QueueTrack> nativeRadioTracks(JammarrPayloads.StationSeed seed, int limit) { nativeCalls++; return nativeTracks; }
        @Override public boolean hasSonicAnalysis(String key) { return !unanalyzed.contains(key); }
        @Override public List<PlexClient.SonicResult> nearest(JammarrPayloads.ItemKind kind, String key, int limit, double maxDistance) { nearestCalls++; return List.of(); }
        @Override public List<QueueTrack> nearestTracks(String key, int limit, double maxDistance) {
            trackDistances.add(maxDistance); return nearestTracks.getOrDefault(maxDistance, List.of());
        }
        @Override public List<QueueTrack> sonicPath(String startKey, String endKey, int limit) { pathCalls++; return paths.getOrDefault(startKey + "->" + endKey, List.of()); }
        @Override public List<QueueTrack> randomTracks(int limit, Set<String> excluded) { return randomTracks.stream().filter(track -> !excluded.contains(track.key())).toList(); }
        @Override public List<QueueTrack> metadataFallback(List<JammarrPayloads.StationSeed> seeds, int limit, Set<String> excluded) { fallbackCalls++; return fallbackTracks; }
        @Override public List<QueueTrack> expand(JammarrPayloads.ItemKind kind, String key, int limit) throws IOException { return List.of(); }
    }
}
