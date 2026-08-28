package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class PlexServiceTest {
    private enum Mode { NORMAL, UNAUTHORIZED, MALFORMED, TIMEOUT, TRANSCODE_FAILURE, CHUNKED_OVERSIZE, STALLED_BODY, STALLED_POST, NO_PASS, UNANALYZED, OLD_SERVER, NO_PREFERRED_MUSIC, NO_MUSIC_LIBRARY }
    private HttpServer server;
    private PlexService client;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastQuery = new AtomicReference<>(Collections.<String, String>emptyMap());
    private final Map<String, Map<String, String>> queriesByPath = new ConcurrentHashMap<String, Map<String, String>>();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = client(Duration.ofSeconds(2));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void validatesNamedMusicLibraryAndSendsHeaderAuthentication() throws Exception {
        client.validate();
        assertEquals("/library/sections", lastPath.get());
    }

    @Test void blankLibraryPrefersMusicOverAnEarlierAsmrLibrary() throws Exception {
        PlexService automatic = new PlexService(baseUrl(), "secret", "", Duration.ofSeconds(2));
        automatic.validate();
        automatic.browse(ControlPackets.BrowseKind.SEARCH, "Song", 0, 2);
        assertEquals("/library/sections/1/all", lastPath.get());
    }

    @Test void blankLibraryFallsBackToTheFirstValidMusicSection() throws Exception {
        mode.set(Mode.NO_PREFERRED_MUSIC);
        PlexService automatic = new PlexService(baseUrl(), "secret", "", Duration.ofSeconds(2));
        automatic.validate();
        automatic.browse(ControlPackets.BrowseKind.SEARCH, "Song", 0, 2);
        assertEquals("/library/sections/2/all", lastPath.get());
    }

    @Test void blankLibraryFailsWhenPlexHasNoMusicSections() {
        mode.set(Mode.NO_MUSIC_LIBRARY);
        PlexException error = assertThrows(PlexException.class,
                () -> new PlexService(baseUrl(), "secret", "", Duration.ofSeconds(2)).validate());
        assertEquals(PlexException.Kind.CONFIGURATION, error.kind());
    }

    @Test void rejectsInvalidAuthenticationWithTypedFailure() {
        mode.set(Mode.UNAUTHORIZED);
        PlexException error = assertThrows(PlexException.class, client::validate);
        assertEquals(PlexException.Kind.AUTHENTICATION, error.kind());
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test void rejectsInvalidPlexUrlBeforeOpeningAConnection() {
        PlexException error = assertThrows(PlexException.class,
                () -> new PlexService("ftp://plex.example", "secret", "Music", Duration.ofSeconds(2)).validate());
        assertEquals(PlexException.Kind.CONFIGURATION, error.kind());
        assertEquals("Plex URL must be an http(s) URL with a host", error.getMessage());
    }

    @Test void paginatesSearchAndFiltersMalformedMetadataEntries() throws Exception {
        client.validate();
        PlexService.Page page = client.browse(ControlPackets.BrowseKind.SEARCH, "A&B", 2, 2);
        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertEquals("4", lastQuery.get().get("X-Plex-Container-Start"));
        assertEquals("3", lastQuery.get().get("X-Plex-Container-Size"));
        assertEquals("A&B", lastQuery.get().get("title"));
        assertEquals("10", lastQuery.get().get("type"));
    }

    @Test void browsesAudioPlaylistsAndExpandsTrackSnapshots() throws Exception {
        client.validate();
        PlexService.Page page = client.browse(ControlPackets.BrowseKind.PLAYLISTS, "", 0, 20);
        assertEquals(StationModels.ItemKind.PLAYLIST, page.items().get(0).kind());
        assertEquals(1, page.items().size());
        assertEquals("audio", queriesByPath.get("/playlists").get("playlistType"));
        assertEquals("1", queriesByPath.get("/playlists").get("sectionID"));
        QueueTrack track = client.expand(StationModels.ItemKind.PLAYLIST, "88", 2).get(0);
        assertEquals("2", lastQuery.get().get("X-Plex-Container-Size"));
        assertEquals("Song", track.title()); assertEquals("Artist", track.artist()); assertEquals("Album", track.album());
    }

    @Test void detectsSonicCapabilityAndReadsNearestTracksAndPaths() throws Exception {
        client.validate();
        PlexService.SonicStatus status = client.sonicStatus();
        assertEquals(StationModels.SonicCapability.READY, status.capability());
        List<QueueTrack> nearest = client.nearestTracks("42", 10, 0.25);
        assertEquals(Arrays.asList("43", "44"), nearest.stream().map(QueueTrack::key).collect(Collectors.toList()));
        assertEquals("0.25", lastQuery.get().get("maxDistance"));
        List<QueueTrack> path = client.sonicPath("42", "44", 100);
        assertEquals(Arrays.asList("42", "43", "44"), path.stream().map(QueueTrack::key).collect(Collectors.toList()));
        assertEquals("42", lastQuery.get().get("startID")); assertEquals("44", lastQuery.get().get("endID"));
    }

    @Test void reportsDistinctPlexPassAnalysisAndServerCapabilityFailures() throws Exception {
        client.validate(); mode.set(Mode.NO_PASS); assertEquals(StationModels.SonicCapability.NO_PLEX_PASS, client.sonicStatus().capability());
        mode.set(Mode.UNANALYZED); assertEquals(StationModels.SonicCapability.ANALYSIS_INCOMPLETE, client.sonicStatus().capability());
        mode.set(Mode.OLD_SERVER); assertEquals(StationModels.SonicCapability.UNSUPPORTED, client.sonicStatus().capability());
    }

    @Test void randomTracksExcludeRecentHistory() throws Exception {
        client.validate();
        List<QueueTrack> tracks = client.randomTracks(2, Collections.singleton("42"));
        assertEquals(Arrays.asList("43", "44"), tracks.stream().map(QueueTrack::key).collect(Collectors.toList()));
        assertEquals("random", lastQuery.get().get("sort"));
    }

    @Test void usesAnAdvertisedNativeArtistRadioPlayQueue() throws Exception {
        client.validate(); client.sonicStatus();
        List<QueueTrack> tracks = client.nativeRadioTracks(new StationModels.StationSeed(StationModels.ItemKind.ARTIST, "77", "Artist", ""), 10);
        assertEquals(Arrays.asList("43", "44"), tracks.stream().map(QueueTrack::key).collect(Collectors.toList()));
        assertEquals("POST", lastMethod.get()); assertEquals("audio", lastQuery.get().get("type"));
        assertEquals("1", lastQuery.get().get("continuous")); assertTrue(lastQuery.get().get("uri").contains("/library/metadata/77/station/native"));
    }

    @Test void filtersItemsFromOtherMusicLibrariesAcrossUnscopedEndpoints() throws Exception {
        client.validate(); client.sonicStatus();
        assertEquals(Arrays.asList("43", "44"), client.nearestTracks("42", 10, 0.25).stream()
                .map(QueueTrack::key).collect(Collectors.toList()));
        assertEquals(Arrays.asList("43", "44"), client.nativeRadioTracks(
                new StationModels.StationSeed(StationModels.ItemKind.ARTIST, "77", "Artist", ""), 10).stream()
                .map(QueueTrack::key).collect(Collectors.toList()));
        PlexService.Page playlists = client.browse(ControlPackets.BrowseKind.PLAYLISTS, "", 0, 20);
        assertEquals(Collections.singletonList("88"), playlists.items().stream()
                .map(StationModels.MediaItem::key).collect(Collectors.toList()));
    }

    @Test void boundsNativeStationBodyReadTimeoutsAfterHeadersArrive() throws Exception {
        PlexService bounded = client(Duration.ofMillis(50));
        bounded.validate(); bounded.sonicStatus(); mode.set(Mode.STALLED_POST);
        PlexException error = assertThrows(PlexException.class, () -> bounded.nativeRadioTracks(
                new StationModels.StationSeed(StationModels.ItemKind.ARTIST, "77", "Artist", ""), 10));
        assertEquals(PlexException.Kind.OFFLINE, error.kind());
        assertEquals("Plex station body timed out", error.getMessage());
    }

    @Test void reportsMalformedJsonWithoutLeakingResponseDetails() {
        mode.set(Mode.MALFORMED);
        PlexException error = assertThrows(PlexException.class, client::validate);
        assertEquals(PlexException.Kind.INVALID_RESPONSE, error.kind());
        assertEquals("Plex returned malformed JSON", error.getMessage());
    }

    @Test void boundsRequestTimeouts() {
        mode.set(Mode.TIMEOUT);
        PlexException error = assertThrows(PlexException.class, () -> client(Duration.ofMillis(50)).validate());
        assertEquals(PlexException.Kind.OFFLINE, error.kind());
    }

    @Test void boundsMetadataBodyReadTimeoutsAfterHeadersArrive() {
        mode.set(Mode.STALLED_BODY);
        PlexException error = assertThrows(PlexException.class, () -> client(Duration.ofMillis(50)).validate());
        assertEquals(PlexException.Kind.OFFLINE, error.kind());
        assertEquals("Plex metadata body timed out", error.getMessage());
    }

    @Test void rejectsChunkedMetadataThatExceedsTheReadLimit() {
        mode.set(Mode.CHUNKED_OVERSIZE);
        PlexException error = assertThrows(PlexException.class,
                () -> new PlexService(baseUrl(), "secret", "Music", Duration.ofSeconds(2), 64, PlexService.MAX_TRANSCODE_BYTES).validate());
        assertEquals(PlexException.Kind.INVALID_RESPONSE, error.kind());
        assertEquals("Plex metadata response exceeds the safety limit", error.getMessage());
    }

    @Test void transcodeUsesRequiredGenericProfileAndWritesBody() throws Exception {
        Path output = Files.createTempFile("jammarr-test-", ".mp3");
        try {
            client.transcode(track(), output, 160);
            assertEquals(2088, Files.size(output));
            assertEquals("1", lastQuery.get().get("download"));
            assertEquals("160", lastQuery.get().get("maxAudioBitrate"));
            assertEquals("160", lastQuery.get().get("audioBitrate"));
            assertEquals("2", lastQuery.get().get("audioChannelCount"));
            assertEquals("Generic", lastQuery.get().get("X-Plex-Client-Profile-Name"));
            assertEquals("secret", lastQuery.get().get("X-Plex-Token"));
        } finally { Files.deleteIfExists(output); }
    }

    @Test void classifiesTranscodeFailureAndDoesNotLeaveOutput() throws Exception {
        mode.set(Mode.TRANSCODE_FAILURE);
        Path output = Files.createTempFile("jammarr-test-", ".mp3");
        try {
            PlexException error = assertThrows(PlexException.class, () -> client.transcode(track(), output, 160));
            assertEquals(PlexException.Kind.TRANSCODE, error.kind());
            assertFalse(Files.exists(output));
        } finally { Files.deleteIfExists(output); }
    }

    @Test void rejectsOversizedTranscodesBeforeWritingThemToTheCache() throws Exception {
        Path output = Files.createTempFile("jammarr-test-", ".mp3");
        try {
            PlexService bounded = new PlexService(baseUrl(), "secret", "Music", Duration.ofSeconds(2), PlexService.MAX_JSON_BYTES, 1_024);
            PlexException error = assertThrows(PlexException.class, () -> bounded.transcode(track(), output, 160));
            assertEquals(PlexException.Kind.INVALID_RESPONSE, error.kind());
            assertFalse(Files.exists(output));
        } finally { Files.deleteIfExists(output); }
    }

    @Test void rejectsTracksBeyondTheDurationSafetyLimitWithoutContactingPlex() throws Exception {
        Path output = Files.createTempFile("jammarr-test-", ".mp3");
        try {
            PlexException error = assertThrows(PlexException.class, () -> client.transcode(
                    new QueueTrack("42", "Too long", "Artist", "Album", PlexService.MAX_TRACK_DURATION_MS + 1), output, 160));
            assertEquals(PlexException.Kind.INVALID_RESPONSE, error.kind());
        } finally { Files.deleteIfExists(output); }
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private PlexService client(Duration timeout) { return new PlexService(baseUrl(), "secret", "Music", timeout); }
    private static QueueTrack track() { return new QueueTrack("42", "Song", "Artist", "Album", 123_000); }

    private void respond(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastMethod.set(exchange.getRequestMethod());
        lastQuery.set(query(exchange.getRequestURI().getRawQuery()));
        queriesByPath.put(exchange.getRequestURI().getPath(), lastQuery.get());
        Mode current = mode.get();
        if (current == Mode.TIMEOUT) {
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (current == Mode.UNAUTHORIZED) { send(exchange, 401, "{}"); return; }
        if (current == Mode.MALFORMED) { send(exchange, 200, "not-json"); return; }
        if (current == Mode.CHUNKED_OVERSIZE) {
            byte[] bytes = ("{\"MediaContainer\":{\"Directory\":[" + repeat(' ', 256) + "]}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(bytes); exchange.close(); return;
        }
        if (current == Mode.STALLED_BODY) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"MediaContainer\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close(); return;
        }
        if (exchange.getRequestURI().getPath().contains("transcode")) {
            assertNull(exchange.getRequestHeaders().getFirst("X-Plex-Token"));
            if (current == Mode.TRANSCODE_FAILURE) { send(exchange, 500, "failed"); return; }
            byte[] data = new byte[522 * 4];
            for (int offset = 0; offset < data.length; offset += 522) {
                data[offset] = (byte) 0xff; data[offset + 1] = (byte) 0xfb;
                data[offset + 2] = (byte) 0xa0; data[offset + 3] = 0;
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg"); exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data); exchange.close(); return;
        }
        assertEquals("secret", exchange.getRequestHeaders().getFirst("X-Plex-Token"));
        String path = exchange.getRequestURI().getPath();
        if (current == Mode.STALLED_POST && path.equals("/playQueues")) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"MediaContainer\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close(); return;
        }
        String body;
        if ("/".equals(path)) {
            body = current == Mode.NO_PASS ? "{\"MediaContainer\":{\"myPlexSubscription\":false,\"version\":\"1.41.0\"}}"
                    : current == Mode.OLD_SERVER ? "{\"MediaContainer\":{\"myPlexSubscription\":true,\"version\":\"1.23.9\"}}"
                    : "{\"MediaContainer\":{\"myPlexSubscription\":true,\"version\":\"1.41.0\",\"machineIdentifier\":\"machine\"}}";
        } else if ("/library/sections".equals(path)) {
            body = current == Mode.NO_MUSIC_LIBRARY
                    ? "{\"MediaContainer\":{\"Directory\":[{\"type\":\"movie\",\"key\":\"9\",\"title\":\"Films\"}]}}"
                    : current == Mode.NO_PREFERRED_MUSIC
                    ? "{\"MediaContainer\":{\"Directory\":[{\"type\":\"artist\",\"key\":\"2\",\"title\":\"ASMR\"}]}}"
                    : "{\"MediaContainer\":{\"Directory\":[{\"type\":\"movie\",\"key\":\"9\",\"title\":\"Films\"},{\"type\":\"artist\",\"key\":\"2\",\"title\":\"ASMR\"},{\"type\":\"artist\",\"key\":\"1\",\"title\":\"Music\"}]}}";
        } else if ("/library/sections/1/all".equals(path)) {
            body = current == Mode.UNANALYZED ? "{\"MediaContainer\":{\"Metadata\":[]}}"
                    : "{\"MediaContainer\":{\"librarySectionID\":\"1\",\"Metadata\":[{\"type\":\"track\",\"ratingKey\":\"42\",\"title\":\"Song\",\"grandparentTitle\":\"Artist\",\"duration\":123000,\"musicAnalysisVersion\":1},{\"type\":\"track\",\"title\":\"Missing key\"},\"bad\",{\"type\":\"track\",\"ratingKey\":\"43\",\"title\":\"Song 2\",\"grandparentTitle\":\"Artist 2\",\"duration\":124000},{\"type\":\"track\",\"ratingKey\":\"44\",\"title\":\"Overflow\",\"grandparentTitle\":\"Artist 3\",\"duration\":125000}]}}";
        } else if ("/library/metadata/42/nearest".equals(path)) {
            body = "{\"MediaContainer\":{\"Metadata\":[{\"type\":\"track\",\"librarySectionID\":\"1\",\"ratingKey\":\"42\",\"title\":\"Seed\"},{\"type\":\"track\",\"librarySectionID\":\"2\",\"ratingKey\":\"99\",\"title\":\"Private ASMR\",\"distance\":0.05},{\"type\":\"track\",\"librarySectionID\":\"1\",\"ratingKey\":\"43\",\"title\":\"Near\",\"grandparentTitle\":\"Artist 2\",\"duration\":124000,\"distance\":0.1},{\"type\":\"track\",\"librarySectionID\":\"1\",\"ratingKey\":\"44\",\"title\":\"Farther\",\"grandparentTitle\":\"Artist 3\",\"duration\":125000,\"distance\":0.2}]}}";
        } else if ("/library/sections/1/computePath".equals(path)) {
            body = "{\"MediaContainer\":{\"Metadata\":[{\"type\":\"track\",\"ratingKey\":\"42\",\"title\":\"Start\",\"duration\":1000},{\"type\":\"track\",\"ratingKey\":\"43\",\"title\":\"Middle\",\"duration\":1000},{\"type\":\"track\",\"ratingKey\":\"44\",\"title\":\"End\",\"duration\":1000}]}}";
        } else if ("/library/metadata/77".equals(path)) {
            body = "{\"MediaContainer\":{\"librarySectionID\":\"1\",\"Metadata\":[{\"type\":\"artist\",\"ratingKey\":\"77\",\"title\":\"Artist\",\"musicAnalysisVersion\":1,\"Stations\":{\"Metadata\":[{\"key\":\"/library/metadata/77/station/native?type=10\"}]}}]}}";
        } else if ("/playQueues".equals(path)) {
            body = "{\"MediaContainer\":{\"playQueueID\":1,\"Metadata\":[{\"type\":\"track\",\"librarySectionID\":\"2\",\"ratingKey\":\"99\",\"title\":\"Private ASMR\"},{\"type\":\"track\",\"librarySectionID\":\"1\",\"ratingKey\":\"43\",\"title\":\"Native 1\",\"grandparentTitle\":\"Artist 2\",\"duration\":124000},{\"type\":\"track\",\"librarySectionID\":\"1\",\"ratingKey\":\"44\",\"title\":\"Native 2\",\"grandparentTitle\":\"Artist 3\",\"duration\":125000}]}}";
        } else if ("/playlists".equals(path)) {
            body = "{\"MediaContainer\":{\"Metadata\":[{\"type\":\"playlist\",\"ratingKey\":\"88\",\"title\":\"Road Trip\"},{\"type\":\"playlist\",\"ratingKey\":\"89\",\"title\":\"Private ASMR\"}]}}";
        } else if ("/playlists/88/items".equals(path) || "/library/metadata/42".equals(path)) {
            body = "{\"MediaContainer\":{\"librarySectionID\":\"1\",\"totalSize\":1,\"Metadata\":[{\"type\":\"track\",\"ratingKey\":\"42\",\"title\":\"Song\",\"grandparentTitle\":\"Artist\",\"parentTitle\":\"Album\",\"duration\":123000,\"musicAnalysisVersion\":1}]}}";
        } else if ("/playlists/89/items".equals(path)) {
            body = "{\"MediaContainer\":{\"librarySectionID\":\"2\",\"totalSize\":1,\"Metadata\":[{\"type\":\"track\",\"ratingKey\":\"99\",\"title\":\"Private ASMR\",\"duration\":123000}]}}";
        } else {
            body = "{\"MediaContainer\":{}}";
        }
        send(exchange, 200, body);
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.<String, String>emptyMap();
        Map<String, String> values = new HashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length == 1 ? "" : decode(parts[1]));
        }
        return Collections.unmodifiableMap(new HashMap<String, String>(values));
    }
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

}
