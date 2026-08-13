package stonytark.pampmod.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import stonytark.pampmod.network.PampPayloads;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlexClientTest {
    private enum Mode { NORMAL, UNAUTHORIZED, MALFORMED, TIMEOUT, TRANSCODE_FAILURE }
    private HttpServer server;
    private PlexClient client;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastQuery = new AtomicReference<>(Map.of());

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

    @Test void rejectsInvalidAuthenticationWithTypedFailure() {
        mode.set(Mode.UNAUTHORIZED);
        PlexException error = assertThrows(PlexException.class, client::validate);
        assertEquals(PlexException.Kind.AUTHENTICATION, error.kind());
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test void paginatesSearchAndFiltersMalformedMetadataEntries() throws Exception {
        client.validate();
        PlexClient.Page page = client.browse(PampPayloads.BrowseKind.SEARCH, "A&B", 2, 2);
        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertEquals("4", lastQuery.get().get("X-Plex-Container-Start"));
        assertEquals("3", lastQuery.get().get("X-Plex-Container-Size"));
        assertEquals("A&B", lastQuery.get().get("title"));
        assertEquals("10", lastQuery.get().get("type"));
    }

    @Test void browsesAudioPlaylistsAndExpandsTrackSnapshots() throws Exception {
        client.validate();
        PlexClient.Page page = client.browse(PampPayloads.BrowseKind.PLAYLISTS, "", 0, 20);
        assertEquals(PampPayloads.ItemKind.PLAYLIST, page.items().getFirst().kind());
        assertEquals("audio", lastQuery.get().get("playlistType"));
        QueueTrack track = client.expand(PampPayloads.ItemKind.PLAYLIST, "88").getFirst();
        assertEquals("Song", track.title()); assertEquals("Artist", track.artist()); assertEquals("Album", track.album());
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

    @Test void transcodeUsesRequiredGenericProfileAndWritesBody() throws Exception {
        Path output = Files.createTempFile("pampmod-test-", ".mp3");
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
        Path output = Files.createTempFile("pampmod-test-", ".mp3");
        try {
            PlexException error = assertThrows(PlexException.class, () -> client.transcode(track(), output, 160));
            assertEquals(PlexException.Kind.TRANSCODE, error.kind());
            assertFalse(Files.exists(output));
        } finally { Files.deleteIfExists(output); }
    }

    private PlexClient client(Duration timeout) { return new PlexClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret", "Music", timeout); }
    private static QueueTrack track() { return new QueueTrack("42", "Song", "Artist", "Album", 123_000); }

    private void respond(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastQuery.set(query(exchange.getRequestURI().getRawQuery()));
        Mode current = mode.get();
        if (current == Mode.TIMEOUT) {
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (current == Mode.UNAUTHORIZED) { send(exchange, 401, "{}"); return; }
        if (current == Mode.MALFORMED) { send(exchange, 200, "not-json"); return; }
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
        String body = switch (path) {
            case "/library/sections" -> "{\"MediaContainer\":{\"Directory\":[{\"type\":\"movie\",\"key\":\"9\",\"title\":\"Films\"},{\"type\":\"artist\",\"key\":\"1\",\"title\":\"Music\"}]}}";
            case "/library/sections/1/all" -> "{\"MediaContainer\":{\"Metadata\":[{\"type\":\"track\",\"ratingKey\":\"42\",\"title\":\"Song\",\"grandparentTitle\":\"Artist\",\"duration\":123000},{\"type\":\"track\",\"title\":\"Missing key\"},\"bad\",{\"type\":\"track\",\"ratingKey\":\"43\",\"title\":\"Song 2\"},{\"type\":\"track\",\"ratingKey\":\"44\",\"title\":\"Overflow\"}]}}";
            case "/playlists" -> "{\"MediaContainer\":{\"Metadata\":[{\"type\":\"playlist\",\"ratingKey\":\"88\",\"title\":\"Road Trip\"}]}}";
            case "/playlists/88/items", "/library/metadata/42" -> "{\"MediaContainer\":{\"Metadata\":[{\"type\":\"track\",\"ratingKey\":\"42\",\"title\":\"Song\",\"grandparentTitle\":\"Artist\",\"parentTitle\":\"Album\",\"duration\":123000}]}}";
            default -> "{\"MediaContainer\":{}}";
        };
        send(exchange, 200, body);
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> values = new HashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return Map.copyOf(values);
    }
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
