package stonytark.pampmod.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import stonytark.pampmod.config.PampConfig;
import stonytark.pampmod.network.PampPayloads;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class PlexClient {
    private static final System.Logger LOGGER = System.getLogger(PlexClient.class.getName());
    private static final String CLIENT_ID = "f0b09ec2674d42f4a802c5cc9a57d774";
    static final int MAX_EXPANDED_TRACKS = 500;
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    static final long MAX_TRANSCODE_BYTES = 256L * 1024 * 1024;
    static final long MAX_TRACK_DURATION_MS = 3L * 60 * 60 * 1_000;
    private static final Duration TRANSCODE_READ_TIMEOUT = Duration.ofMinutes(3);
    private static final ScheduledExecutorService READ_TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("pampmod-http-timeouts").factory());
    public record Page(List<PampPayloads.MediaItem> items, boolean hasMore) {}
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Supplier<String> configuredUrl;
    private final Supplier<String> configuredToken;
    private final Supplier<String> configuredLibrary;
    private final int maxJsonBytes;
    private final long maxTranscodeBytes;
    private final Duration transcodeReadTimeout;
    private volatile String libraryKey;

    public PlexClient() { this(() -> PampConfig.PLEX_URL.get(), PampConfig::plexToken, () -> PampConfig.MUSIC_LIBRARY.get(), Duration.ofSeconds(15)); }
    PlexClient(String url, String token, String library) { this(() -> url, () -> token, () -> library, Duration.ofSeconds(15)); }
    PlexClient(String url, String token, String library, Duration requestTimeout) { this(() -> url, () -> token, () -> library, requestTimeout); }
    PlexClient(String url, String token, String library, Duration requestTimeout, int maxJsonBytes, long maxTranscodeBytes) {
        this(() -> url, () -> token, () -> library, requestTimeout, maxJsonBytes, maxTranscodeBytes, requestTimeout);
    }
    private PlexClient(Supplier<String> url, Supplier<String> token, Supplier<String> library, Duration requestTimeout) {
        this(url, token, library, requestTimeout, MAX_JSON_BYTES, MAX_TRANSCODE_BYTES, TRANSCODE_READ_TIMEOUT);
    }
    private PlexClient(Supplier<String> url, Supplier<String> token, Supplier<String> library, Duration requestTimeout,
                       int maxJsonBytes, long maxTranscodeBytes, Duration transcodeReadTimeout) {
        this.configuredUrl = url; this.configuredToken = token; this.configuredLibrary = library;
        this.requestTimeout = requestTimeout;
        this.maxJsonBytes = maxJsonBytes;
        this.maxTranscodeBytes = maxTranscodeBytes;
        this.transcodeReadTimeout = transcodeReadTimeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(requestTimeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void validate() throws IOException, InterruptedException {
        String token = token();
        if (token.isBlank()) throw new PlexException(PlexException.Kind.CONFIGURATION, "Plex token is not configured");
        if (baseUrl().startsWith("http://")) LOGGER.log(System.Logger.Level.WARNING, "PAmpMod Plex connection uses unencrypted HTTP; use this only on a trusted private network");
        JsonObject root = getJson("/library/sections", "");
        JsonArray directories = array(container(root), "Directory");
        String wanted = configuredLibrary.get().trim();
        for (JsonElement element : directories) {
            JsonObject section = element.getAsJsonObject();
            if (!"artist".equals(text(section, "type"))) continue;
            if (wanted.isBlank() || wanted.equals(text(section, "key")) || wanted.equalsIgnoreCase(text(section, "title"))) {
                libraryKey = text(section, "key"); return;
            }
        }
        throw new PlexException(PlexException.Kind.CONFIGURATION, "No matching Plex music library was found");
    }

    public Page browse(PampPayloads.BrowseKind kind, String query, int page, int pageSize) throws IOException, InterruptedException {
        ensureLibrary(); int start = Math.max(0, page) * pageSize;
        String path; String params = "X-Plex-Container-Start=" + start + "&X-Plex-Container-Size=" + (pageSize + 1);
        switch (kind) {
            case SEARCH -> { path = "/library/sections/" + libraryKey + "/all"; params += "&type=10&title=" + encode(query); }
            case ARTISTS -> { path = "/library/sections/" + libraryKey + "/all"; params += "&type=8&sort=titleSort:asc"; }
            case ALBUMS -> { path = "/library/sections/" + libraryKey + "/all"; params += "&type=9&sort=titleSort:asc"; }
            case PLAYLISTS -> { path = "/playlists"; params += "&playlistType=audio&sort=titleSort:asc"; }
            default -> throw new IOException("Unsupported browse category");
        }
        JsonArray metadata = array(container(getJson(path, params)), "Metadata");
        List<PampPayloads.MediaItem> items = new ArrayList<>();
        for (JsonElement element : metadata) {
            PampPayloads.MediaItem item = mediaItem(element);
            if (item != null) items.add(item);
        }
        boolean more = items.size() > pageSize;
        if (more) items = new ArrayList<>(items.subList(0, pageSize));
        return new Page(List.copyOf(items), more);
    }

    public List<QueueTrack> expand(PampPayloads.ItemKind kind, String key) throws IOException, InterruptedException {
        return expand(kind, key, MAX_EXPANDED_TRACKS);
    }

    public List<QueueTrack> expand(PampPayloads.ItemKind kind, String key, int limit) throws IOException, InterruptedException {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_EXPANDED_TRACKS));
        String path = switch (kind) {
            case TRACK -> "/library/metadata/" + encodePath(key);
            case ALBUM -> "/library/metadata/" + encodePath(key) + "/children";
            case ARTIST -> "/library/metadata/" + encodePath(key) + "/allLeaves";
            case PLAYLIST -> "/playlists/" + encodePath(key) + "/items";
        };
        String params = kind == PampPayloads.ItemKind.TRACK ? ""
                : "X-Plex-Container-Start=0&X-Plex-Container-Size=" + boundedLimit;
        JsonArray metadata = array(container(getJson(path, params)), "Metadata");
        List<QueueTrack> tracks = new ArrayList<>();
        for (JsonElement element : metadata) {
            if (tracks.size() >= boundedLimit) break;
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            if (!"track".equals(text(value, "type"))) continue;
            String ratingKey = text(value, "ratingKey"), title = text(value, "title");
            if (ratingKey.isBlank() || title.isBlank()) continue;
            tracks.add(new QueueTrack(ratingKey, title, text(value, "grandparentTitle"), text(value, "parentTitle"), number(value, "duration")));
        }
        return List.copyOf(tracks);
    }

    public void transcode(QueueTrack track, Path output, int bitrate) throws IOException, InterruptedException {
        if (track.durationMs() > MAX_TRACK_DURATION_MS) {
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex track exceeds the three-hour safety limit");
        }
        Path plexOutput = Files.createTempFile(output.toAbsolutePath().getParent(), "pamp-plex-", ".mp3");
        try {
            downloadTranscode(track, plexOutput, bitrate);
            Mp3FrameIndex.Info info;
            try { info = Mp3FrameIndex.inspect(Files.readAllBytes(plexOutput)); }
            catch (IllegalArgumentException invalid) { throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned invalid MP3 audio", invalid); }
            if (info.constantBitrate() && info.bitrateKbps() == bitrate && info.channels() == 2) {
                Files.move(plexOutput, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Mp3CbrNormalizer.normalize(plexOutput, output, bitrate);
            }
            if (Files.size(output) > maxTranscodeBytes) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Normalized Plex audio exceeds the configured safety limit");
            }
            Mp3FrameIndex.Info normalized = Mp3FrameIndex.inspect(Files.readAllBytes(output));
            if (!normalized.constantBitrate() || normalized.bitrateKbps() != bitrate || normalized.channels() != 2) {
                throw new PlexException(PlexException.Kind.TRANSCODE, "Unable to produce constant-bitrate stereo MP3 audio");
            }
        } catch (IllegalArgumentException invalid) {
            Files.deleteIfExists(output);
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Normalized Plex audio is invalid", invalid);
        } catch (IOException failure) {
            Files.deleteIfExists(output);
            throw failure;
        } finally {
            Files.deleteIfExists(plexOutput);
        }
    }

    private void downloadTranscode(QueueTrack track, Path output, int bitrate) throws IOException, InterruptedException {
        String sessionId = UUID.randomUUID().toString();
        String profile = "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=mp3&audioCodec=mp3)";
        String params = "path=" + encode("/library/metadata/" + track.key())
                + "&session=" + encode(sessionId) + "&protocol=http&download=1&directPlay=0&directStream=0"
                + "&maxAudioBitrate=" + bitrate + "&musicBitrate=" + bitrate + "&audioBitrate=" + bitrate + "&audioChannelCount=2"
                // PMS requires its client identity and token in this endpoint's query, even though
                // ordinary library requests accept the same values as headers.
                + "&X-Plex-Client-Identifier=" + CLIENT_ID
                + "&X-Plex-Device-Name=" + encode("Minecraft Server")
                + "&X-Plex-Product=PAmpMod&X-Plex-Platform=Java&X-Plex-Version=1.0.0"
                + "&X-Plex-Provides=player&X-Plex-Client-Profile-Name=Generic"
                + "&X-Plex-Client-Profile-Extra=" + encode(profile)
                + "&X-Plex-Token=" + encode(token());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/music/:/transcode/universal/start.mp3?" + params))
                .GET().timeout(Duration.ofMinutes(3)).build();
        HttpResponse<InputStream> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (HttpTimeoutException e) { throw new PlexException(PlexException.Kind.OFFLINE, "Plex transcode timed out", e); }
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            PlexException.Kind kind = response.statusCode() == 401 || response.statusCode() == 403 ? PlexException.Kind.AUTHENTICATION
                    : response.statusCode() == 404 ? PlexException.Kind.NOT_FOUND : PlexException.Kind.TRANSCODE;
            throw new PlexException(kind, "Plex transcode returned HTTP " + response.statusCode());
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declaredLength > maxTranscodeBytes) {
            response.body().close();
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex transcode exceeds the configured safety limit");
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        InputStream body = response.body();
        ScheduledFuture<?> timeout = READ_TIMEOUTS.schedule(() -> {
            timedOut.set(true);
            try { body.close(); } catch (IOException ignored) {}
        }, transcodeReadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try (InputStream in = body; var out = Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            copyLimited(in, out, maxTranscodeBytes, "Plex transcode exceeds the configured safety limit");
        } catch (IOException failure) {
            if (timedOut.get()) throw new PlexException(PlexException.Kind.OFFLINE, "Plex transcode body timed out", failure);
            throw failure;
        } finally {
            timeout.cancel(false);
        }
        if (Files.size(output) < 1024) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned an empty audio stream");
    }

    private PampPayloads.MediaItem mediaItem(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject value = element.getAsJsonObject();
        PampPayloads.ItemKind kind = switch (text(value, "type")) {
            case "artist" -> PampPayloads.ItemKind.ARTIST;
            case "album" -> PampPayloads.ItemKind.ALBUM;
            case "playlist" -> PampPayloads.ItemKind.PLAYLIST;
            default -> PampPayloads.ItemKind.TRACK;
        };
        String key = text(value, "ratingKey"), title = text(value, "title");
        if (key.isBlank() || title.isBlank()) return null;
        String subtitle = kind == PampPayloads.ItemKind.TRACK ? text(value, "grandparentTitle") : text(value, "parentTitle");
        return new PampPayloads.MediaItem(kind, key, title, subtitle, number(value, "duration"));
    }

    private void ensureLibrary() throws IOException, InterruptedException { if (libraryKey == null) validate(); }
    private JsonObject getJson(String path, String query) throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try { response = http.send(request(path, query).timeout(requestTimeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (HttpTimeoutException e) { throw new PlexException(PlexException.Kind.OFFLINE, "Plex request timed out", e); }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new PlexException(PlexException.Kind.AUTHENTICATION, "Plex rejected the configured token");
            if (response.statusCode() == 404) throw new PlexException(PlexException.Kind.NOT_FOUND, "Plex item was not found");
            if (response.statusCode() / 100 != 2) throw new PlexException(PlexException.Kind.OFFLINE, "Plex returned HTTP " + response.statusCode());
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > maxJsonBytes) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex metadata response exceeds the safety limit");
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> timeout = READ_TIMEOUTS.schedule(() -> {
                timedOut.set(true);
                try { body.close(); } catch (IOException ignored) {}
            }, requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            byte[] bytes;
            try {
                bytes = readLimited(body, maxJsonBytes, "Plex metadata response exceeds the safety limit");
            } catch (IOException failure) {
                if (timedOut.get()) throw new PlexException(PlexException.Kind.OFFLINE, "Plex metadata body timed out", failure);
                throw failure;
            } finally {
                timeout.cancel(false);
            }
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("root is not an object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned malformed JSON", malformed);
        }
    }

    private static byte[] readLimited(InputStream input, int maximumBytes, String message) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, message);
        return bytes;
    }

    private static void copyLimited(InputStream input, java.io.OutputStream output, long maximumBytes, String message) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read == 0) continue;
            total += read;
            if (total > maximumBytes) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, message);
            output.write(buffer, 0, read);
        }
    }
    private HttpRequest.Builder request(String path, String query) {
        String separator = query == null || query.isBlank() ? "" : "?" + query;
        return HttpRequest.newBuilder(URI.create(baseUrl() + path + separator)).header("Accept", "application/json")
                .header("X-Plex-Token", token()).header("X-Plex-Product", "PAmpMod")
                .header("X-Plex-Version", "1.0.0").header("X-Plex-Client-Identifier", CLIENT_ID);
    }
    private static JsonObject container(JsonObject root) { JsonElement e = root.get("MediaContainer"); return e == null || !e.isJsonObject() ? new JsonObject() : e.getAsJsonObject(); }
    private static JsonArray array(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray(); }
    private static String text(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
    private static long number(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || e.isJsonNull() ? 0 : e.getAsLong(); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String encodePath(String value) { return encode(value).replace("+", "%20"); }
    private String baseUrl() { String value = configuredUrl.get().trim(); return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private String token() { return configuredToken.get(); }
}
