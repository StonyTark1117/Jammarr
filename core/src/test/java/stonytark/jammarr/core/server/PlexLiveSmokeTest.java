package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java 8-compatible live Plex smoke reused by every Minecraft family build. */
public final class PlexLiveSmokeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "JAMMARR_LIVE_TEST", matches = "true")
    public void validatesSonicStationsAdventureAndTranscode() throws Exception {
        PlexService plex = new PlexService(required("JAMMARR_PLEX_URL"),
                required("JAMMARR_PLEX_TOKEN"), environment("JAMMARR_PLEX_LIBRARY"));
        plex.validate();
        PlexService.SonicStatus sonic = plex.sonicStatus();
        assertEquals(StationModels.SonicCapability.READY, sonic.capability(), sonic.message());

        PlexService.Page albums = plex.browse(ControlPackets.BrowseKind.ALBUMS, "", 0, 20);
        assertFalse(albums.items().isEmpty(), "The live Plex music library has no albums");
        StationModels.MediaItem album = albums.items().get(0);
        List<QueueTrack> albumTracks = plex.expand(StationModels.ItemKind.ALBUM, album.key());
        assertFalse(albumTracks.isEmpty(), "The first live Plex album has no playable tracks");
        QueueTrack track = albumTracks.get(0);
        PlexService.Page search = plex.browse(ControlPackets.BrowseKind.SEARCH, track.title(), 0, 20);
        assertTrue(contains(search.items(), track.key()), "Exact-title live Plex search missed its track");
        String privateSentinel = environment("JAMMARR_PRIVATE_PLEX_SENTINEL").trim();
        if (!privateSentinel.isEmpty()) {
            assertTrue(plex.browse(ControlPackets.BrowseKind.SEARCH, privateSentinel, 0, 20).items().isEmpty(),
                    "Selected-library isolation returned content from another Plex music library");
        }

        List<QueueTrack> analyzed = plex.analyzedTracks(10);
        assertTrue(analyzed.size() >= 2, "The live Plex library did not return two analyzed tracks");
        QueueTrack sonicSeed = null;
        List<QueueTrack> neighbors = Collections.emptyList();
        for (QueueTrack candidate : analyzed) {
            List<QueueTrack> candidateNeighbors = plex.nearestTracks(candidate.key(), 25, 0.40);
            if (!candidateNeighbors.isEmpty()) {
                sonicSeed = candidate;
                neighbors = candidateNeighbors;
                break;
            }
        }
        assertFalse(neighbors.isEmpty(), "The live Plex analyzed tracks have no sonic neighbors");
        QueueTrack sonicNeighbor = neighbors.get(0);
        List<QueueTrack> path = plex.sonicPath(sonicSeed.key(), sonicNeighbor.key(), 100);
        assertTrue(path.size() >= 2, "The live Plex server did not produce a Sonic Adventure path");

        StationGenerator generator = new StationGenerator(plex);
        StationModels.StationSeed first = seed(sonicSeed);
        StationModels.StationSeed second = seed(sonicNeighbor);
        assertFalse(generator.generate(new StationModels.StationDefinition(
                        StationModels.StationType.TRACK_RADIO, "Track Radio",
                        Collections.singletonList(first), 1L), Collections.<QueueTrack>emptyList(),
                sonic.capability(), false).tracks().isEmpty());
        assertFalse(generator.generate(new StationModels.StationDefinition(
                        StationModels.StationType.LIBRARY_SHUFFLE, "Library Shuffle",
                        Collections.<StationModels.StationSeed>emptyList(), 2L),
                Collections.<QueueTrack>emptyList(), sonic.capability(), false).tracks().isEmpty());

        PlexService.Page artists = plex.browse(ControlPackets.BrowseKind.ARTISTS, "", 0, 20);
        StationModels.MediaItem artist = firstOfKind(artists.items(), StationModels.ItemKind.ARTIST);
        assertFalse(generator.generate(new StationModels.StationDefinition(
                        StationModels.StationType.ARTIST_RADIO, "Artist Radio",
                        Collections.singletonList(seed(artist)), 3L), Collections.<QueueTrack>emptyList(),
                sonic.capability(), false).tracks().isEmpty());
        assertFalse(generator.generate(new StationModels.StationDefinition(
                        StationModels.StationType.ALBUM_RADIO, "Album Radio",
                        Collections.singletonList(seed(album)), 4L), Collections.<QueueTrack>emptyList(),
                sonic.capability(), false).tracks().isEmpty());
        assertFalse(generator.generate(new StationModels.StationDefinition(
                        StationModels.StationType.SONIC_MIX, "Sonic Mix", Arrays.asList(first, second), 5L),
                Collections.<QueueTrack>emptyList(), sonic.capability(), false).tracks().isEmpty());
        assertTrue(generator.generate(new StationModels.StationDefinition(
                        StationModels.StationType.SONIC_ADVENTURE, "Adventure",
                        Arrays.asList(first, second), 6L), Collections.<QueueTrack>emptyList(),
                sonic.capability(), false).adventurePath());

        List<QueueTrack> history = new ArrayList<QueueTrack>(analyzed.subList(0, Math.min(5, analyzed.size())));
        for (int transition = 0; transition < 30; transition++) {
            StationGenerator.GeneratedBatch batch = generator.generate(new StationModels.StationDefinition(
                            StationModels.StationType.AUTOPLAY, "Autoplay",
                            Collections.<StationModels.StationSeed>emptyList(), 7L + transition),
                    history, sonic.capability(), false);
            QueueTrack selected = batch.tracks().get(0);
            assertFalse(containsTrack(history, selected.key()),
                    "Autoplay repeated a recent track at transition " + transition);
            history.add(selected);
            while (history.size() > StationGenerator.TRACK_HISTORY_LIMIT) history.remove(0);
        }

        Path output = Files.createTempFile("jammarr-core-live-", ".mp3");
        try {
            plex.transcode(track, output, 160);
            byte[] bytes = Files.readAllBytes(output);
            assertTrue(bytes.length > 1024, "The live Plex transcode was empty");
            Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(bytes);
            assertTrue(info.constantBitrate(), "The live Plex transcode was not constant bitrate");
            assertEquals(160, info.bitrateKbps(), "The live Plex transcode ignored 160 kbps");
            assertEquals(2, info.channels(), "The live Plex transcode was not stereo");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static boolean contains(List<StationModels.MediaItem> items, String key) {
        for (StationModels.MediaItem item : items) if (item.key().equals(key)) return true;
        return false;
    }

    private static boolean containsTrack(List<QueueTrack> tracks, String key) {
        for (QueueTrack track : tracks) if (track.key().equals(key)) return true;
        return false;
    }

    private static StationModels.MediaItem firstOfKind(List<StationModels.MediaItem> items,
                                                        StationModels.ItemKind kind) {
        for (StationModels.MediaItem item : items) if (item.kind() == kind) return item;
        throw new AssertionError("The live Plex library has no " + kind.name().toLowerCase() + " entries");
    }

    private static StationModels.StationSeed seed(QueueTrack track) {
        return new StationModels.StationSeed(StationModels.ItemKind.TRACK,
                track.key(), track.title(), track.artist());
    }

    private static StationModels.StationSeed seed(StationModels.MediaItem item) {
        return new StationModels.StationSeed(item.kind(), item.key(), item.title(), item.subtitle());
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static String required(String name) {
        String value = environment(name);
        if (value.trim().isEmpty()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
