package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.MediaItem;
import stonytark.jammarr.core.model.StationModels.SonicCapability;
import stonytark.jammarr.core.model.StationModels.SonicResult;
import stonytark.jammarr.core.model.StationModels.StationDefinition;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.model.StationModels.StationType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationGeneratorTest {
    @Test void prefersAdvertisedNativeRadioBeforeSonicNearest() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.nativeTracks = Collections.singletonList(track("native", "New Artist"));
        StationGenerator.GeneratedBatch result = new StationGenerator(catalog).generate(
                station(StationType.ARTIST_RADIO, seed(ItemKind.ARTIST, "artist")), Collections.<QueueTrack>emptyList(),
                SonicCapability.READY, false);
        assertEquals(Collections.singletonList("native"), keys(result.tracks()));
        assertEquals(1, catalog.nativeCalls);
        assertEquals(0, catalog.nearestCalls);
    }

    @Test void widensTrackDistanceOnceAndSuppressesRecentTracksAndArtists() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.nearestTracks.put(0.25, Collections.<QueueTrack>emptyList());
        catalog.nearestTracks.put(0.40, Arrays.asList(track("old-key", "Fresh"),
                track("same-artist", "Old Artist"), track("chosen", "Fresh")));
        List<QueueTrack> history = Arrays.asList(track("old-key", "Earlier"), track("prior", "Old Artist"));
        StationGenerator.GeneratedBatch result = new StationGenerator(catalog).generate(
                station(StationType.TRACK_RADIO, seed(ItemKind.TRACK, "seed")), history,
                SonicCapability.READY, false);
        assertEquals(Collections.singletonList("chosen"), keys(result.tracks()));
        assertEquals(Arrays.asList(0.25, 0.40), catalog.trackDistances);
    }

    @Test void gatesMetadataFallbackAndNeverUsesItForAdventure() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.fallbackTracks = Collections.singletonList(track("fallback", "Artist"));
        StationGenerator generator = new StationGenerator(catalog);
        StationDefinition radio = station(StationType.TRACK_RADIO, seed(ItemKind.TRACK, "seed"));
        assertThrows(PlexException.class, () -> generator.generate(radio, Collections.<QueueTrack>emptyList(),
                SonicCapability.NO_PLEX_PASS, false));
        assertEquals(Collections.singletonList("fallback"), keys(generator.generate(radio,
                Collections.<QueueTrack>emptyList(), SonicCapability.NO_PLEX_PASS, true).tracks()));
        StationDefinition adventure = station(StationType.SONIC_ADVENTURE,
                seed(ItemKind.TRACK, "one"), seed(ItemKind.TRACK, "two"));
        assertThrows(PlexException.class, () -> generator.generate(adventure, Collections.<QueueTrack>emptyList(),
                SonicCapability.NO_PLEX_PASS, true));
        assertEquals(1, catalog.fallbackCalls);
    }

    @Test void adventurePreservesWaypointOrderAndRejectsIncompletePaths() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.paths.put("one->two", Arrays.asList(track("one", "A"), track("middle", "B"), track("two", "C")));
        catalog.paths.put("two->three", Arrays.asList(track("two", "C"), track("three", "D")));
        StationGenerator generator = new StationGenerator(catalog);
        StationDefinition adventure = station(StationType.SONIC_ADVENTURE, seed(ItemKind.TRACK, "one"),
                seed(ItemKind.TRACK, "two"), seed(ItemKind.TRACK, "three"));
        assertEquals(Arrays.asList("one", "middle", "two", "three"), keys(generator.generate(
                adventure, Collections.<QueueTrack>emptyList(), SonicCapability.READY, false).tracks()));
        catalog.paths.put("two->three", Collections.singletonList(track("two", "C")));
        PlexException partial = assertThrows(PlexException.class, () -> generator.generate(
                adventure, Collections.<QueueTrack>emptyList(), SonicCapability.READY, false));
        assertTrue(partial.getMessage().contains("waypoint 2 and 3"));
    }

    @Test void adventureRejectsUnanalyzedWaypointBeforeComputingPaths() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.unanalyzed.add("two");
        StationDefinition adventure = station(StationType.SONIC_ADVENTURE,
                seed(ItemKind.TRACK, "one"), seed(ItemKind.TRACK, "two"));
        PlexException missing = assertThrows(PlexException.class, () -> new StationGenerator(catalog).generate(
                adventure, Collections.<QueueTrack>emptyList(), SonicCapability.READY, false));
        assertTrue(missing.getMessage().contains("waypoint 2"));
        assertEquals(0, catalog.pathCalls);
    }

    @Test void validatesSeedShapesAndBounds() {
        assertThrows(PlexException.class, () -> StationGenerator.validate(station(StationType.SONIC_MIX,
                seed(ItemKind.TRACK, "1"), seed(ItemKind.ARTIST, "2"))));
        assertThrows(PlexException.class, () -> StationGenerator.validate(station(StationType.SONIC_ADVENTURE,
                seed(ItemKind.TRACK, "1"))));
        assertThrows(PlexException.class, () -> StationGenerator.validate(station(StationType.TRACK_RADIO,
                seed(ItemKind.TRACK, "  "))));
    }

    private static StationDefinition station(StationType type, StationSeed... seeds) {
        return new StationDefinition(type, type.name(), Arrays.asList(seeds), 1);
    }

    private static StationSeed seed(ItemKind kind, String key) {
        return new StationSeed(kind, key, key, "Artist");
    }

    private static QueueTrack track(String key, String artist) {
        return new QueueTrack(key, key, artist, "Album", 1_000);
    }

    private static List<String> keys(List<QueueTrack> tracks) {
        List<String> keys = new ArrayList<String>();
        for (QueueTrack track : tracks) keys.add(track.key());
        return keys;
    }

    private static final class FakeCatalog implements StationCatalog {
        private List<QueueTrack> nativeTracks = Collections.emptyList();
        private List<QueueTrack> fallbackTracks = Collections.emptyList();
        private List<QueueTrack> randomTracks = Collections.emptyList();
        private final Map<Double, List<QueueTrack>> nearestTracks = new HashMap<Double, List<QueueTrack>>();
        private final Map<String, List<QueueTrack>> paths = new HashMap<String, List<QueueTrack>>();
        private final Set<String> unanalyzed = new HashSet<String>();
        private final List<Double> trackDistances = new ArrayList<Double>();
        private int nativeCalls;
        private int nearestCalls;
        private int fallbackCalls;
        private int pathCalls;

        @Override public List<QueueTrack> nativeRadioTracks(StationSeed seed, int limit) {
            nativeCalls++;
            return nativeTracks;
        }
        @Override public boolean hasSonicAnalysis(String key) { return !unanalyzed.contains(key); }
        @Override public List<SonicResult> nearest(ItemKind kind, String key, int limit, double maxDistance) {
            nearestCalls++;
            return Collections.emptyList();
        }
        @Override public List<QueueTrack> nearestTracks(String key, int limit, double maxDistance) {
            trackDistances.add(maxDistance);
            List<QueueTrack> values = nearestTracks.get(maxDistance);
            return values == null ? Collections.<QueueTrack>emptyList() : values;
        }
        @Override public List<QueueTrack> sonicPath(String startKey, String endKey, int limit) {
            pathCalls++;
            List<QueueTrack> values = paths.get(startKey + "->" + endKey);
            return values == null ? Collections.<QueueTrack>emptyList() : values;
        }
        @Override public List<QueueTrack> randomTracks(int limit, Set<String> excluded) {
            List<QueueTrack> values = new ArrayList<QueueTrack>();
            for (QueueTrack track : randomTracks) if (!excluded.contains(track.key())) values.add(track);
            return values;
        }
        @Override public List<QueueTrack> metadataFallback(List<StationSeed> seeds, int limit, Set<String> excluded) {
            fallbackCalls++;
            return fallbackTracks;
        }
        @Override public List<QueueTrack> expand(ItemKind kind, String key, int limit) { return Collections.emptyList(); }
    }
}
