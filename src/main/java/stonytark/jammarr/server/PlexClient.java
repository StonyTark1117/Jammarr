package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.network.BoundedStreams;
import stonytark.jammarr.core.network.HttpTransport;
import stonytark.jammarr.core.network.UrlConnectionHttpTransport;
import stonytark.jammarr.core.server.Mp3FrameIndex;
import stonytark.jammarr.core.server.Mp3CbrNormalizer;
import stonytark.jammarr.core.server.PlexException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrPayloads;
import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class PlexClient implements StationCatalog {
    private static final System.Logger LOGGER = System.getLogger(PlexClient.class.getName());
    private static final String CLIENT_ID = "f0b09ec2674d42f4a802c5cc9a57d774";
    static final int MAX_EXPANDED_TRACKS = 500;
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    static final long MAX_TRANSCODE_BYTES = 256L * 1024 * 1024;
    static final long MAX_TRACK_DURATION_MS = 3L * 60 * 60 * 1_000;
    private static final Duration TRANSCODE_READ_TIMEOUT = Duration.ofMinutes(3);
    public record Page(List<JammarrPayloads.MediaItem> items, boolean hasMore) {}
    public record SonicStatus(JammarrPayloads.SonicCapability capability, String message) {}
    public record SonicResult(JammarrPayloads.MediaItem item, double distance) {}
    private final HttpTransport http;
    private final Duration requestTimeout;
    private final Supplier<String> configuredUrl;
    private final Supplier<String> configuredToken;
    private final Supplier<String> configuredLibrary;
    private final int maxJsonBytes;
    private final long maxTranscodeBytes;
    private final Duration transcodeReadTimeout;
    private volatile String libraryKey;
    private volatile String machineIdentifier;

    public PlexClient() { this(() -> JammarrSettings.plexUrl(), JammarrSettings::plexToken, () -> JammarrSettings.musicLibrary(), Duration.ofSeconds(15)); }
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
        this.http = new UrlConnectionHttpTransport();
    }

    public void validate() throws IOException, InterruptedException {
        String token = token();
        if (token.isBlank()) throw new PlexException(PlexException.Kind.CONFIGURATION, "Plex token is not configured");
        validateBaseUrl();
        if (baseUrl().startsWith("http://")) LOGGER.log(System.Logger.Level.WARNING, "Jammarr Plex connection uses unencrypted HTTP; use this only on a trusted private network");
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

    public SonicStatus sonicStatus() throws IOException, InterruptedException {
        ensureLibrary();
        JsonObject identity = container(getJson("/", ""));
        machineIdentifier = text(identity, "machineIdentifier");
        if (!supportsSonic(text(identity, "version"))) {
            return new SonicStatus(JammarrPayloads.SonicCapability.UNSUPPORTED,
                    "Plex Media Server 1.24.0 or newer is required for sonic stations");
        }
        JsonElement subscription = identity.get("myPlexSubscription");
        if (subscription != null && !subscription.isJsonNull() && !subscription.getAsBoolean()) {
            return new SonicStatus(JammarrPayloads.SonicCapability.NO_PLEX_PASS,
                    "Plex Pass is not active for the configured server account");
        }
        String params = "type=10&musicAnalysisVersion=1&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=1";
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        if (metadata.isEmpty() || !metadata.get(0).isJsonObject()) {
            return new SonicStatus(JammarrPayloads.SonicCapability.ANALYSIS_INCOMPLETE,
                    "The Plex music library has no analyzed tracks");
        }
        long analysisVersion = number(metadata.get(0).getAsJsonObject(), "musicAnalysisVersion");
        if (analysisVersion < 1) {
            return new SonicStatus(JammarrPayloads.SonicCapability.ANALYSIS_INCOMPLETE,
                    "Plex sonic analysis is disabled, incomplete, or missing for this library");
        }
        return new SonicStatus(JammarrPayloads.SonicCapability.READY, "Plex sonic analysis is ready");
    }

    public List<QueueTrack> nativeRadioTracks(JammarrPayloads.StationSeed seed, int limit) throws IOException, InterruptedException {
        if (seed.kind() == JammarrPayloads.ItemKind.PLAYLIST) return List.of();
        JsonArray metadata = array(container(getJson("/library/metadata/" + encodePath(seed.key()), "includeStations=1")), "Metadata");
        if (metadata.isEmpty() || !metadata.get(0).isJsonObject()) return List.of();
        JsonObject item = metadata.get(0).getAsJsonObject(); JsonElement stationsElement = item.get("Stations");
        if (stationsElement == null || !stationsElement.isJsonObject()) return List.of();
        JsonArray stations = array(stationsElement.getAsJsonObject(), "Metadata");
        if (stations.isEmpty() || !stations.get(0).isJsonObject()) return List.of();
        String stationKey = text(stations.get(0).getAsJsonObject(), "key"); if (stationKey.isBlank()) return List.of();
        String machine = machineIdentifier;
        if (machine == null || machine.isBlank()) {
            machine = text(container(getJson("/", "")), "machineIdentifier"); machineIdentifier = machine;
        }
        if (machine.isBlank()) return List.of();
        String uri = "server://" + machine + "/com.plexapp.plugins.library" + stationKey;
        JsonArray queue = array(container(postJson("/playQueues", "type=audio&includeRelated=1&continuous=1&uri=" + encode(uri))), "Metadata");
        List<QueueTrack> tracks = new ArrayList<>();
        for (JsonElement element : queue) { QueueTrack track = queueTrack(element); if (track != null) tracks.add(track); if (tracks.size() >= Math.max(1, Math.min(limit, 100))) break; }
        return List.copyOf(tracks);
    }

    public boolean hasSonicAnalysis(String key) throws IOException, InterruptedException {
        JsonArray metadata = array(container(getJson("/library/metadata/" + encodePath(key), "")), "Metadata");
        return !metadata.isEmpty() && metadata.get(0).isJsonObject() && number(metadata.get(0).getAsJsonObject(), "musicAnalysisVersion") >= 1;
    }

    public List<QueueTrack> analyzedTracks(int limit) throws IOException, InterruptedException {
        ensureLibrary(); int bounded = Math.max(1, Math.min(limit, 100));
        String params = "type=10&musicAnalysisVersion=1&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=" + bounded;
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        List<QueueTrack> results = new ArrayList<>();
        for (JsonElement element : metadata) { QueueTrack track = queueTrack(element); if (track != null) results.add(track); }
        return List.copyOf(results);
    }

    public List<SonicResult> nearest(JammarrPayloads.ItemKind kind, String key, int limit, double maxDistance)
            throws IOException, InterruptedException {
        if (kind == JammarrPayloads.ItemKind.PLAYLIST) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Playlists cannot be sonic seeds");
        int bounded = Math.max(1, Math.min(limit, 100));
        double distance = Math.max(0.0, Math.min(maxDistance, 1.0));
        JsonArray metadata = array(container(getJson("/library/metadata/" + encodePath(key) + "/nearest",
                "limit=" + bounded + "&maxDistance=" + distance)), "Metadata");
        List<SonicResult> results = new ArrayList<>();
        for (JsonElement element : metadata) {
            JammarrPayloads.MediaItem item = mediaItem(element);
            if (item == null || item.kind() != kind || item.key().equals(key)) continue;
            JsonObject value = element.getAsJsonObject();
            results.add(new SonicResult(item, decimal(value, "distance")));
        }
        return List.copyOf(results);
    }

    public List<QueueTrack> nearestTracks(String key, int limit, double maxDistance) throws IOException, InterruptedException {
        int bounded = Math.max(1, Math.min(limit, 100));
        JsonArray metadata = array(container(getJson("/library/metadata/" + encodePath(key) + "/nearest",
                "limit=" + bounded + "&maxDistance=" + Math.max(0.0, Math.min(maxDistance, 1.0)))), "Metadata");
        List<QueueTrack> results = new ArrayList<>();
        for (JsonElement element : metadata) {
            QueueTrack track = queueTrack(element);
            if (track != null && !track.key().equals(key)) results.add(track);
        }
        return List.copyOf(results);
    }

    public List<QueueTrack> sonicPath(String startKey, String endKey, int limit) throws IOException, InterruptedException {
        ensureLibrary(); int bounded = Math.max(2, Math.min(limit, 100));
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/computePath",
                "startID=" + encode(startKey) + "&endID=" + encode(endKey))), "Metadata");
        List<QueueTrack> results = new ArrayList<>();
        for (JsonElement element : metadata) {
            QueueTrack track = queueTrack(element);
            if (track != null) results.add(track);
            if (results.size() >= bounded) break;
        }
        return List.copyOf(results);
    }

    public List<QueueTrack> randomTracks(int limit, Set<String> excluded) throws IOException, InterruptedException {
        ensureLibrary(); int bounded = Math.max(1, Math.min(limit, 100));
        String params = "type=10&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=" + Math.min(100, bounded * 4);
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        List<QueueTrack> results = new ArrayList<>();
        for (JsonElement element : metadata) {
            QueueTrack track = queueTrack(element);
            if (track != null && !excluded.contains(track.key())) results.add(track);
            if (results.size() >= bounded) break;
        }
        return List.copyOf(results);
    }

    public List<QueueTrack> metadataFallback(List<JammarrPayloads.StationSeed> seeds, int limit, Set<String> excluded)
            throws IOException, InterruptedException {
        LinkedHashSet<QueueTrack> results = new LinkedHashSet<>();
        for (JammarrPayloads.StationSeed seed : seeds) {
            if (seed.kind() == JammarrPayloads.ItemKind.PLAYLIST) continue;
            for (QueueTrack track : metadataRelated(seed, Math.max(1, limit))) {
                if (!excluded.contains(track.key())) results.add(track);
                if (results.size() >= limit) return List.copyOf(results);
            }
        }
        results.addAll(randomTracks(Math.max(1, limit - results.size()), excluded));
        return List.copyOf(results).stream().limit(limit).toList();
    }

    private List<QueueTrack> metadataRelated(JammarrPayloads.StationSeed seed, int limit) throws IOException, InterruptedException {
        JsonArray metadata = array(container(getJson("/library/metadata/" + encodePath(seed.key()), "")), "Metadata");
        if (metadata.isEmpty() || !metadata.get(0).isJsonObject()) return List.of();
        JsonObject item = metadata.get(0).getAsJsonObject(); LinkedHashSet<String> genres = tags(item, "Genre"), styles = tags(item, "Style");
        if (seed.kind() == JammarrPayloads.ItemKind.TRACK && genres.isEmpty() && styles.isEmpty()) {
            String parent = text(item, "parentRatingKey"), artist = text(item, "grandparentRatingKey");
            for (String related : List.of(parent, artist)) {
                if (related.isBlank()) continue;
                JsonArray relatedMetadata = array(container(getJson("/library/metadata/" + encodePath(related), "")), "Metadata");
                if (!relatedMetadata.isEmpty() && relatedMetadata.get(0).isJsonObject()) {
                    genres.addAll(tags(relatedMetadata.get(0).getAsJsonObject(), "Genre")); styles.addAll(tags(relatedMetadata.get(0).getAsJsonObject(), "Style"));
                }
            }
        }
        List<QueueTrack> results = new ArrayList<>();
        for (String genre : genres) appendMetadataMatches(results, "genre", genre, limit);
        for (String style : styles) appendMetadataMatches(results, "style", style, limit);
        if (results.isEmpty()) results.addAll(expand(seed.kind(), seed.key(), limit));
        return results.stream().distinct().limit(limit).toList();
    }

    private void appendMetadataMatches(List<QueueTrack> output, String field, String value, int limit) throws IOException, InterruptedException {
        if (output.size() >= limit) return;
        String params = "type=10&" + field + "=" + encode(value) + "&sort=userRating:desc,ratingCount:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=" + Math.min(100, limit * 2);
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        for (JsonElement element : metadata) { QueueTrack track = queueTrack(element); if (track != null && output.stream().noneMatch(existing -> existing.key().equals(track.key()))) output.add(track); if (output.size() >= limit) break; }
    }

    private static LinkedHashSet<String> tags(JsonObject object, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement element : array(object, key)) if (element.isJsonObject()) { String value = text(element.getAsJsonObject(), "tag"); if (!value.isBlank()) values.add(value); }
        return values;
    }

    public Page browse(JammarrPayloads.BrowseKind kind, String query, int page, int pageSize) throws IOException, InterruptedException {
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
        List<JammarrPayloads.MediaItem> items = new ArrayList<>();
        for (JsonElement element : metadata) {
            JammarrPayloads.MediaItem item = mediaItem(element);
            if (item != null) items.add(item);
        }
        boolean more = items.size() > pageSize;
        if (more) items = new ArrayList<>(items.subList(0, pageSize));
        return new Page(List.copyOf(items), more);
    }

    public List<QueueTrack> expand(JammarrPayloads.ItemKind kind, String key) throws IOException, InterruptedException {
        return expand(kind, key, MAX_EXPANDED_TRACKS);
    }

    public List<QueueTrack> expand(JammarrPayloads.ItemKind kind, String key, int limit) throws IOException, InterruptedException {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_EXPANDED_TRACKS));
        String path = switch (kind) {
            case TRACK -> "/library/metadata/" + encodePath(key);
            case ALBUM -> "/library/metadata/" + encodePath(key) + "/children";
            case ARTIST -> "/library/metadata/" + encodePath(key) + "/allLeaves";
            case PLAYLIST -> "/playlists/" + encodePath(key) + "/items";
        };
        String params = kind == JammarrPayloads.ItemKind.TRACK ? ""
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
        Path plexOutput = Files.createTempFile(output.toAbsolutePath().getParent(), "jammarr-plex-", ".mp3");
        try {
            downloadTranscode(track, plexOutput, bitrate);
            Mp3FrameIndex.Info info;
            try { info = Mp3FrameIndex.inspect(Files.readAllBytes(plexOutput)); }
            catch (IllegalArgumentException invalid) { throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned invalid MP3 audio", invalid); }
            if (info.constantBitrate() && info.bitrateKbps() == bitrate && info.channels() == 2) {
                Files.move(plexOutput, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                try {
                    Mp3CbrNormalizer.normalize(plexOutput, output, bitrate);
                } catch (IOException normalizationFailure) {
                    // A valid Plex MP3 is still playable when normalization is unavailable.
                    // Preserve it rather than dropping an otherwise usable track.
                    LOGGER.log(System.Logger.Level.WARNING, "Jammarr could not normalize Plex track " + track.key()
                            + "; using the validated source MP3 instead");
                    Files.move(plexOutput, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.size(output) > maxTranscodeBytes) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Normalized Plex audio exceeds the configured safety limit");
            }
            Mp3FrameIndex.Info normalized = Mp3FrameIndex.inspect(Files.readAllBytes(output));
            if (normalized.channels() != 2) {
                throw new PlexException(PlexException.Kind.TRANSCODE, "Unable to produce stereo MP3 audio");
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
                + "&X-Plex-Product=Jammarr&X-Plex-Platform=Java&X-Plex-Version=1.0.0"
                + "&X-Plex-Provides=player&X-Plex-Client-Profile-Name=Generic"
                + "&X-Plex-Client-Profile-Extra=" + encode(profile)
                + "&X-Plex-Token=" + encode(token());
        URL url = new URL(baseUrl() + "/music/:/transcode/universal/start.mp3?" + params);
        HttpTransport.Response response;
        try {
            response = http.open("GET", url, Map.of(), timeoutMillis(Duration.ofMinutes(3)), timeoutMillis(transcodeReadTimeout));
        } catch (SocketTimeoutException timeout) {
            throw new PlexException(PlexException.Kind.OFFLINE, "Plex transcode timed out", timeout);
        }
        try (response; var out = Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (response.statusCode() / 100 != 2) {
                PlexException.Kind kind = response.statusCode() == 401 || response.statusCode() == 403 ? PlexException.Kind.AUTHENTICATION
                        : response.statusCode() == 404 ? PlexException.Kind.NOT_FOUND : PlexException.Kind.TRANSCODE;
                throw new PlexException(kind, "Plex transcode returned HTTP " + response.statusCode());
            }
            if (response.contentLength() > maxTranscodeBytes) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex transcode exceeds the configured safety limit");
            }
            try {
                BoundedStreams.copy(response.body(), out, maxTranscodeBytes,
                        "Plex transcode exceeds the configured safety limit");
            } catch (SocketTimeoutException timeout) {
                throw new PlexException(PlexException.Kind.OFFLINE, "Plex transcode body timed out", timeout);
            }
        }
        if (Files.size(output) < 1024) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned an empty audio stream");
    }

    private JammarrPayloads.MediaItem mediaItem(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject value = element.getAsJsonObject();
        JammarrPayloads.ItemKind kind = switch (text(value, "type")) {
            case "artist" -> JammarrPayloads.ItemKind.ARTIST;
            case "album" -> JammarrPayloads.ItemKind.ALBUM;
            case "playlist" -> JammarrPayloads.ItemKind.PLAYLIST;
            default -> JammarrPayloads.ItemKind.TRACK;
        };
        String key = text(value, "ratingKey"), title = text(value, "title");
        if (key.isBlank() || title.isBlank()) return null;
        String subtitle = kind == JammarrPayloads.ItemKind.TRACK ? text(value, "grandparentTitle") : text(value, "parentTitle");
        return new JammarrPayloads.MediaItem(kind, key, title, subtitle, number(value, "duration"));
    }

    private QueueTrack queueTrack(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject value = element.getAsJsonObject();
        if (!"track".equals(text(value, "type"))) return null;
        String key = text(value, "ratingKey"), title = text(value, "title");
        if (key.isBlank() || title.isBlank()) return null;
        return new QueueTrack(key, title, text(value, "grandparentTitle"), text(value, "parentTitle"), number(value, "duration"));
    }

    private void ensureLibrary() throws IOException, InterruptedException { if (libraryKey == null) validate(); }
    private JsonObject getJson(String path, String query) throws IOException, InterruptedException {
        HttpTransport.Response response;
        try { response = open("GET", path, query); }
        catch (SocketTimeoutException e) { throw new PlexException(PlexException.Kind.OFFLINE, "Plex request timed out", e); }
        try (response) {
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new PlexException(PlexException.Kind.AUTHENTICATION, "Plex rejected the configured token");
            if (response.statusCode() == 404) throw new PlexException(PlexException.Kind.NOT_FOUND, "Plex item was not found");
            if (response.statusCode() / 100 != 2) throw new PlexException(PlexException.Kind.OFFLINE, "Plex returned HTTP " + response.statusCode());
            if (response.contentLength() > maxJsonBytes) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex metadata response exceeds the safety limit");
            byte[] bytes;
            try {
                bytes = BoundedStreams.read(response.body(), maxJsonBytes, "Plex metadata response exceeds the safety limit");
            } catch (SocketTimeoutException timeout) {
                throw new PlexException(PlexException.Kind.OFFLINE, "Plex metadata body timed out", timeout);
            }
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("root is not an object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned malformed JSON", malformed);
        }
    }

    private JsonObject postJson(String path, String query) throws IOException, InterruptedException {
        HttpTransport.Response response;
        try { response = open("POST", path, query); }
        catch (SocketTimeoutException e) { throw new PlexException(PlexException.Kind.OFFLINE, "Plex request timed out", e); }
        try (response) {
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new PlexException(PlexException.Kind.AUTHENTICATION, "Plex rejected the configured token");
            if (response.statusCode() == 404) return new JsonObject();
            if (response.statusCode() / 100 != 2) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex station request returned HTTP " + response.statusCode());
            if (response.contentLength() > maxJsonBytes) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex station response exceeds the safety limit");
            byte[] bytes;
            try {
                bytes = BoundedStreams.read(response.body(), maxJsonBytes, "Plex station response exceeds the safety limit");
            } catch (SocketTimeoutException timeout) {
                throw new PlexException(PlexException.Kind.OFFLINE, "Plex station body timed out", timeout);
            }
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("root is not an object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) { throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned malformed station JSON", malformed); }
    }

    private HttpTransport.Response open(String method, String path, String query) throws IOException {
        String separator = query == null || query.isBlank() ? "" : "?" + query;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-Plex-Token", token());
        headers.put("X-Plex-Product", "Jammarr");
        headers.put("X-Plex-Version", "1.0.0");
        headers.put("X-Plex-Client-Identifier", CLIENT_ID);
        return http.open(method, new URL(baseUrl() + path + separator), headers,
                timeoutMillis(requestTimeout), timeoutMillis(requestTimeout));
    }
    private static int timeoutMillis(Duration duration) { return (int)Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis())); }
    private static JsonObject container(JsonObject root) { JsonElement e = root.get("MediaContainer"); return e == null || !e.isJsonObject() ? new JsonObject() : e.getAsJsonObject(); }
    private static JsonArray array(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray(); }
    private static String text(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
    private static long number(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || e.isJsonNull() ? 0 : e.getAsLong(); }
    private static double decimal(JsonObject object, String key) { JsonElement e = object.get(key); return e == null || e.isJsonNull() ? 0 : e.getAsDouble(); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String encodePath(String value) { return encode(value).replace("+", "%20"); }
    private static boolean supportsSonic(String version) {
        if (version == null || version.isBlank()) return true;
        String[] parts = version.split("\\.");
        try { int major = Integer.parseInt(parts[0]), minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0; return major > 1 || major == 1 && minor >= 24; }
        catch (NumberFormatException ignored) { return true; }
    }
    private String baseUrl() { String value = configuredUrl.get().trim(); return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private void validateBaseUrl() throws PlexException {
        String value = baseUrl();
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("unsupported scheme or missing host");
            }
        } catch (IllegalArgumentException invalid) {
            throw new PlexException(PlexException.Kind.CONFIGURATION, "Plex URL must be an http(s) URL with a host", invalid);
        }
    }
    private String token() { return configuredToken.get().trim(); }
}
