package stonytark.pampmod.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import stonytark.pampmod.network.PampPayloads;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration test. Credentials are read only from the process environment. */
class PlexLiveSmokeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "PAMPMOD_LIVE_TEST", matches = "true")
    void browsesAndTranscodesRealPlexMusic() throws Exception {
        String url = required("PAMPMOD_PLEX_URL");
        String token = required("PAMPMOD_PLEX_TOKEN");
        PlexClient plex = new PlexClient(url, token, "");
        plex.validate();

        PlexClient.Page albums = plex.browse(PampPayloads.BrowseKind.ALBUMS, "", 0, 20);
        assertFalse(albums.items().isEmpty(), "The Plex music library has no albums");
        PampPayloads.MediaItem album = albums.items().getFirst();
        QueueTrack track = plex.expand(PampPayloads.ItemKind.ALBUM, album.key()).getFirst();
        PlexClient.Page search = plex.browse(PampPayloads.BrowseKind.SEARCH, track.title(), 0, 20);
        assertTrue(search.items().stream().anyMatch(item -> item.key().equals(track.key())), "Plex track search did not find an exact title");

        Path output = Files.createTempFile("pampmod-live-", ".mp3");
        try {
            plex.transcode(track, output, 160);
            byte[] bytes = Files.readAllBytes(output);
            assertTrue(bytes.length > 1024, "Plex returned an empty transcode");
            assertFalse(Mp3FrameIndex.split(bytes).isEmpty(), "Plex response was not valid Layer III MP3");
            Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(bytes);
            assertTrue(info.constantBitrate(), "Plex response was not constant-bitrate MP3 (range " + info.minimumBitrateKbps() + "-" + info.maximumBitrateKbps() + " kbps)");
            assertEquals(160, info.bitrateKbps(), "Plex did not honor the configured 160-kbps bitrate");
            assertEquals(2, info.channels(), "Plex did not return stereo MP3");
        } finally { Files.deleteIfExists(output); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the live smoke test");
        return value;
    }
}
