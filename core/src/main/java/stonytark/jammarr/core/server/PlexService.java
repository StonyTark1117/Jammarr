package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.MediaItem;
import stonytark.jammarr.core.model.StationModels.SonicCapability;
import stonytark.jammarr.core.model.StationModels.SonicResult;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.protocol.ControlPackets.BrowseKind;
import stonytark.jammarr.core.network.BoundedStreams;
import stonytark.jammarr.core.network.HttpTransport;
import stonytark.jammarr.core.network.UrlConnectionHttpTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import stonytark.jammarr.core.platform.JammarrSettings;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class PlexService implements PlexGateway {
    private static final Logger LOGGER = Logger.getLogger(PlexService.class.getName());
    private static final String CLIENT_ID = "f0b09ec2674d42f4a802c5cc9a57d774";
    public static final int MAX_EXPANDED_TRACKS = 500;
    public static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    public static final long MAX_TRANSCODE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_TRACK_DURATION_MS = 3L * 60 * 60 * 1_000;
    private static final Duration TRANSCODE_READ_TIMEOUT = Duration.ofMinutes(3);
    public static final class Page {
        private final List<MediaItem> items;
        private final boolean hasMore;
        public Page(List<MediaItem> items, boolean hasMore) {
            this.items = immutable(items);
            this.hasMore = hasMore;
        }
        public List<MediaItem> items() { return items; }
        public boolean hasMore() { return hasMore; }
    }
    public static final class SonicStatus {
        private final SonicCapability capability;
        private final String message;
        public SonicStatus(SonicCapability capability, String message) {
            this.capability = capability;
            this.message = message == null ? "" : message;
        }
        public SonicCapability capability() { return capability; }
        public String message() { return message; }
    }
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

    public PlexService() { this(() -> JammarrSettings.plexUrl(), JammarrSettings::plexToken, () -> JammarrSettings.musicLibrary(), Duration.ofSeconds(15)); }
    public PlexService(String url, String token, String library) { this(() -> url, () -> token, () -> library, Duration.ofSeconds(15)); }
    public PlexService(String url, String token, String library, Duration requestTimeout) { this(() -> url, () -> token, () -> library, requestTimeout); }
    public PlexService(String url, String token, String library, Duration requestTimeout, int maxJsonBytes, long maxTranscodeBytes) {
        this(() -> url, () -> token, () -> library, requestTimeout, maxJsonBytes, maxTranscodeBytes, requestTimeout);
    }
    private PlexService(Supplier<String> url, Supplier<String> token, Supplier<String> library, Duration requestTimeout) {
        this(url, token, library, requestTimeout, MAX_JSON_BYTES, MAX_TRANSCODE_BYTES, TRANSCODE_READ_TIMEOUT);
    }
    private PlexService(Supplier<String> url, Supplier<String> token, Supplier<String> library, Duration requestTimeout,
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
        if (blank(token)) throw new PlexException(PlexException.Kind.CONFIGURATION, "Plex token is not configured");
        validateBaseUrl();
        if (baseUrl().startsWith("http://")) LOGGER.warning("Jammarr Plex connection uses unencrypted HTTP; use this only on a trusted private network");
        JsonObject root = getJson("/library/sections", "");
        JsonArray directories = array(container(root), "Directory");
        String wanted = configuredLibrary.get().trim();
        String firstMusicKey = null;
        String preferredMusicKey = null;
        for (JsonElement element : directories) {
            if (!element.isJsonObject()) continue;
            JsonObject section = element.getAsJsonObject();
            if (!"artist".equals(text(section, "type"))) continue;
            String key = text(section, "key");
            if (blank(key)) continue;
            if (!blank(wanted) && (wanted.equals(key) || wanted.equalsIgnoreCase(text(section, "title")))) {
                libraryKey = key; return;
            }
            if (firstMusicKey == null) firstMusicKey = key;
            if ("Music".equalsIgnoreCase(text(section, "title"))) preferredMusicKey = key;
        }
        if (blank(wanted)) {
            libraryKey = preferredMusicKey == null ? firstMusicKey : preferredMusicKey;
            if (libraryKey != null) return;
        }
        throw new PlexException(PlexException.Kind.CONFIGURATION, "No matching Plex music library was found");
    }

    public SonicStatus sonicStatus() throws IOException, InterruptedException {
        ensureLibrary();
        JsonObject identity = container(getJson("/", ""));
        machineIdentifier = text(identity, "machineIdentifier");
        if (!supportsSonic(text(identity, "version"))) {
            return new SonicStatus(SonicCapability.UNSUPPORTED,
                    "Plex Media Server 1.24.0 or newer is required for sonic stations");
        }
        JsonElement subscription = identity.get("myPlexSubscription");
        if (subscription != null && !subscription.isJsonNull() && !subscription.getAsBoolean()) {
            return new SonicStatus(SonicCapability.NO_PLEX_PASS,
                    "Plex Pass is not active for the configured server account");
        }
        String params = "type=10&musicAnalysisVersion=1&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=1";
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        if (metadata.size() == 0 || !metadata.get(0).isJsonObject()) {
            return new SonicStatus(SonicCapability.ANALYSIS_INCOMPLETE,
                    "The Plex music library has no analyzed tracks");
        }
        long analysisVersion = number(metadata.get(0).getAsJsonObject(), "musicAnalysisVersion");
        if (analysisVersion < 1) {
            return new SonicStatus(SonicCapability.ANALYSIS_INCOMPLETE,
                    "Plex sonic analysis is disabled, incomplete, or missing for this library");
        }
        return new SonicStatus(SonicCapability.READY, "Plex sonic analysis is ready");
    }

    public List<QueueTrack> nativeRadioTracks(StationSeed seed, int limit) throws IOException, InterruptedException {
        if (seed.kind() == ItemKind.PLAYLIST) return Collections.emptyList();
        ensureSelectedItem(seed.key());
        JsonArray metadata = array(container(getJson("/library/metadata/" + encodePath(seed.key()), "includeStations=1")), "Metadata");
        if (metadata.size() == 0 || !metadata.get(0).isJsonObject()) return Collections.emptyList();
        JsonObject item = metadata.get(0).getAsJsonObject(); JsonElement stationsElement = item.get("Stations");
        if (stationsElement == null || !stationsElement.isJsonObject()) return Collections.emptyList();
        JsonArray stations = array(stationsElement.getAsJsonObject(), "Metadata");
        if (stations.size() == 0 || !stations.get(0).isJsonObject()) return Collections.emptyList();
        String stationKey = text(stations.get(0).getAsJsonObject(), "key"); if (blank(stationKey)) return Collections.emptyList();
        String machine = machineIdentifier;
        if (blank(machine)) {
            machine = text(container(getJson("/", "")), "machineIdentifier"); machineIdentifier = machine;
        }
        if (blank(machine)) return Collections.emptyList();
        String uri = "server://" + machine + "/com.plexapp.plugins.library" + stationKey;
        JsonObject queueRoot = postJson("/playQueues", "type=audio&includeRelated=1&continuous=1&uri=" + encode(uri));
        JsonObject queueContainer = container(queueRoot);
        JsonArray queue = array(queueContainer, "Metadata");
        List<QueueTrack> tracks = new ArrayList<>();
        for (JsonElement element : queue) {
            QueueTrack track = selectedQueueTrack(queueContainer, element);
            if (track != null) tracks.add(track);
            if (tracks.size() >= Math.max(1, Math.min(limit, 100))) break;
        }
        return immutable(tracks);
    }

    public boolean hasSonicAnalysis(String key) throws IOException, InterruptedException {
        JsonObject root = getJson("/library/metadata/" + encodePath(key), "");
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        return metadata.size() != 0 && metadata.get(0).isJsonObject()
                && belongsToSelectedLibrary(resultContainer, metadata.get(0).getAsJsonObject())
                && number(metadata.get(0).getAsJsonObject(), "musicAnalysisVersion") >= 1;
    }

    public List<QueueTrack> analyzedTracks(int limit) throws IOException, InterruptedException {
        ensureLibrary(); int bounded = Math.max(1, Math.min(limit, 100));
        String params = "type=10&musicAnalysisVersion=1&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=" + bounded;
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        List<QueueTrack> results = new ArrayList<>();
        for (JsonElement element : metadata) { QueueTrack track = queueTrack(element); if (track != null) results.add(track); }
        return immutable(results);
    }

    public List<SonicResult> nearest(ItemKind kind, String key, int limit, double maxDistance)
            throws IOException, InterruptedException {
        if (kind == ItemKind.PLAYLIST) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Playlists cannot be sonic seeds");
        ensureSelectedItem(key);
        int bounded = Math.max(1, Math.min(limit, 100));
        double distance = Math.max(0.0, Math.min(maxDistance, 1.0));
        JsonObject root = getJson("/library/metadata/" + encodePath(key) + "/nearest",
                "limit=" + bounded + "&maxDistance=" + distance);
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        List<SonicResult> results = new ArrayList<>();
        for (JsonElement element : metadata) {
            if (!element.isJsonObject() || !belongsToSelectedLibrary(resultContainer, element.getAsJsonObject())) continue;
            MediaItem item = mediaItem(element);
            if (item == null || item.kind() != kind || item.key().equals(key)) continue;
            JsonObject value = element.getAsJsonObject();
            results.add(new SonicResult(item, decimal(value, "distance")));
        }
        return immutable(results);
    }

    public List<QueueTrack> nearestTracks(String key, int limit, double maxDistance) throws IOException, InterruptedException {
        ensureSelectedItem(key);
        int bounded = Math.max(1, Math.min(limit, 100));
        JsonObject root = getJson("/library/metadata/" + encodePath(key) + "/nearest",
                "limit=" + bounded + "&maxDistance=" + Math.max(0.0, Math.min(maxDistance, 1.0)));
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        List<QueueTrack> results = new ArrayList<>();
        for (JsonElement element : metadata) {
            QueueTrack track = selectedQueueTrack(resultContainer, element);
            if (track != null && !track.key().equals(key)) results.add(track);
        }
        return immutable(results);
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
        return immutable(results);
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
        return immutable(results);
    }

    public List<QueueTrack> metadataFallback(List<StationSeed> seeds, int limit, Set<String> excluded)
            throws IOException, InterruptedException {
        LinkedHashSet<QueueTrack> results = new LinkedHashSet<>();
        for (StationSeed seed : seeds) {
            if (seed.kind() == ItemKind.PLAYLIST) continue;
            for (QueueTrack track : metadataRelated(seed, Math.max(1, limit))) {
                if (!excluded.contains(track.key())) results.add(track);
                if (results.size() >= limit) return immutable(new ArrayList<QueueTrack>(results));
            }
        }
        results.addAll(randomTracks(Math.max(1, limit - results.size()), excluded));
        return first(new ArrayList<QueueTrack>(results), limit);
    }

    private List<QueueTrack> metadataRelated(StationSeed seed, int limit) throws IOException, InterruptedException {
        JsonObject root = getJson("/library/metadata/" + encodePath(seed.key()), "");
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        if (metadata.size() == 0 || !metadata.get(0).isJsonObject()) return Collections.emptyList();
        JsonObject item = metadata.get(0).getAsJsonObject();
        if (!belongsToSelectedLibrary(resultContainer, item)) return Collections.emptyList();
        LinkedHashSet<String> genres = tags(item, "Genre"), styles = tags(item, "Style");
        if (seed.kind() == ItemKind.TRACK && genres.isEmpty() && styles.isEmpty()) {
            String parent = text(item, "parentRatingKey"), artist = text(item, "grandparentRatingKey");
            for (String related : Arrays.asList(parent, artist)) {
                if (blank(related)) continue;
                JsonObject relatedRoot = getJson("/library/metadata/" + encodePath(related), "");
                JsonObject relatedContainer = container(relatedRoot);
                JsonArray relatedMetadata = array(relatedContainer, "Metadata");
                if (relatedMetadata.size() != 0 && relatedMetadata.get(0).isJsonObject()) {
                    JsonObject relatedItem = relatedMetadata.get(0).getAsJsonObject();
                    if (!belongsToSelectedLibrary(relatedContainer, relatedItem)) continue;
                    genres.addAll(tags(relatedItem, "Genre")); styles.addAll(tags(relatedItem, "Style"));
                }
            }
        }
        List<QueueTrack> results = new ArrayList<>();
        for (String genre : genres) appendMetadataMatches(results, "genre", genre, limit);
        for (String style : styles) appendMetadataMatches(results, "style", style, limit);
        if (results.isEmpty()) results.addAll(expand(seed.kind(), seed.key(), limit));
        return distinct(results, limit);
    }

    private void appendMetadataMatches(List<QueueTrack> output, String field, String value, int limit) throws IOException, InterruptedException {
        if (output.size() >= limit) return;
        String params = "type=10&" + field + "=" + encode(value) + "&sort=userRating:desc,ratingCount:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=" + Math.min(100, limit * 2);
        JsonArray metadata = array(container(getJson("/library/sections/" + libraryKey + "/all", params)), "Metadata");
        for (JsonElement element : metadata) { QueueTrack track = queueTrack(element); if (track != null && !containsKey(output, track.key())) output.add(track); if (output.size() >= limit) break; }
    }

    private static LinkedHashSet<String> tags(JsonObject object, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement element : array(object, key)) if (element.isJsonObject()) { String value = text(element.getAsJsonObject(), "tag"); if (!blank(value)) values.add(value); }
        return values;
    }

    public Page browse(BrowseKind kind, String query, int page, int pageSize) throws IOException, InterruptedException {
        ensureLibrary(); int start = Math.max(0, page) * pageSize;
        String path; String params = "X-Plex-Container-Start=" + start + "&X-Plex-Container-Size=" + (pageSize + 1);
        switch (kind) {
            case SEARCH:
                path = "/library/sections/" + libraryKey + "/all";
                params += "&type=10&title=" + encode(query);
                break;
            case ARTISTS:
                path = "/library/sections/" + libraryKey + "/all";
                params += "&type=8&sort=titleSort:asc";
                break;
            case ALBUMS:
                path = "/library/sections/" + libraryKey + "/all";
                params += "&type=9&sort=titleSort:asc";
                break;
            case PLAYLISTS:
                path = "/playlists";
                params += "&playlistType=audio&sectionID=" + encode(libraryKey) + "&sort=titleSort:asc";
                break;
            default:
                throw new IOException("Unsupported browse category");
        }
        JsonArray metadata = array(container(getJson(path, params)), "Metadata");
        List<MediaItem> items = new ArrayList<>();
        for (JsonElement element : metadata) {
            if (kind == BrowseKind.PLAYLISTS && (!element.isJsonObject()
                    || !playlistBelongsToSelectedLibrary(text(element.getAsJsonObject(), "ratingKey")))) continue;
            MediaItem item = mediaItem(element);
            if (item != null) items.add(item);
        }
        boolean more = items.size() > pageSize;
        if (more) items = new ArrayList<>(items.subList(0, pageSize));
        return new Page(immutable(items), more);
    }

    public List<QueueTrack> expand(ItemKind kind, String key) throws IOException, InterruptedException {
        return expand(kind, key, MAX_EXPANDED_TRACKS);
    }

    public List<QueueTrack> expand(ItemKind kind, String key, int limit) throws IOException, InterruptedException {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_EXPANDED_TRACKS));
        String path;
        switch (kind) {
            case TRACK:
                path = "/library/metadata/" + encodePath(key);
                break;
            case ALBUM:
                path = "/library/metadata/" + encodePath(key) + "/children";
                break;
            case ARTIST:
                path = "/library/metadata/" + encodePath(key) + "/allLeaves";
                break;
            case PLAYLIST:
                path = "/playlists/" + encodePath(key) + "/items";
                break;
            default:
                throw new IOException("Unsupported Plex item kind");
        }
        String params = kind == ItemKind.TRACK ? ""
                : "X-Plex-Container-Start=0&X-Plex-Container-Size=" + boundedLimit;
        JsonObject root = getJson(path, params);
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        List<QueueTrack> tracks = new ArrayList<>();
        for (JsonElement element : metadata) {
            if (tracks.size() >= boundedLimit) break;
            QueueTrack track = selectedQueueTrack(resultContainer, element);
            if (track != null) tracks.add(track);
        }
        return immutable(tracks);
    }

    public void transcode(QueueTrack track, Path output, int bitrate) throws IOException, InterruptedException {
        ensureSelectedItem(track.key());
        if (track.durationMs() > MAX_TRACK_DURATION_MS) {
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex track exceeds the three-hour safety limit");
        }
        Path plexOutput = Files.createTempFile(output.toAbsolutePath().getParent(), "jammarr-plex-", ".mp3");
        try {
            downloadTranscode(track, plexOutput, bitrate);
            Mp3FrameIndex.Info info;
            try { info = Mp3FrameIndex.inspect(plexOutput); }
            catch (IllegalArgumentException invalid) { throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned invalid MP3 audio", invalid); }
            if (info.constantBitrate() && info.bitrateKbps() == bitrate && info.channels() == 2) {
                Files.move(plexOutput, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                try {
                    Mp3CbrNormalizer.normalize(plexOutput, output, bitrate);
                } catch (IOException normalizationFailure) {
                    // A valid Plex MP3 is still playable when normalization is unavailable.
                    // Preserve it rather than dropping an otherwise usable track.
                    LOGGER.warning("Jammarr could not normalize Plex track " + track.key()
                            + "; using the validated source MP3 instead");
                    Files.move(plexOutput, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.size(output) > maxTranscodeBytes) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Normalized Plex audio exceeds the configured safety limit");
            }
            Mp3FrameIndex.Info normalized = Mp3FrameIndex.inspect(output);
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
                + "&X-Plex-Product=Jammarr&X-Plex-Platform=Java&X-Plex-Version=1.1.0"
                + "&X-Plex-Provides=player&X-Plex-Client-Profile-Name=Generic"
                + "&X-Plex-Client-Profile-Extra=" + encode(profile)
                + "&X-Plex-Token=" + encode(token());
        URL url = new URL(baseUrl() + "/music/:/transcode/universal/start.mp3?" + params);
        HttpTransport.Response response;
        try {
            response = http.open("GET", url, Collections.<String, String>emptyMap(), timeoutMillis(Duration.ofMinutes(3)), timeoutMillis(transcodeReadTimeout));
        } catch (SocketTimeoutException timeout) {
            throw new PlexException(PlexException.Kind.OFFLINE, "Plex transcode timed out", timeout);
        }
        try (HttpTransport.Response closeable = response;
             OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
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

    private MediaItem mediaItem(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject value = element.getAsJsonObject();
        String type = text(value, "type");
        ItemKind kind;
        if ("artist".equals(type)) kind = ItemKind.ARTIST;
        else if ("album".equals(type)) kind = ItemKind.ALBUM;
        else if ("playlist".equals(type)) kind = ItemKind.PLAYLIST;
        else kind = ItemKind.TRACK;
        String key = text(value, "ratingKey"), title = text(value, "title");
        if (blank(key) || blank(title)) return null;
        String subtitle = kind == ItemKind.TRACK ? text(value, "grandparentTitle") : text(value, "parentTitle");
        return new MediaItem(kind, key, title, subtitle, number(value, "duration"));
    }

    private QueueTrack queueTrack(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject value = element.getAsJsonObject();
        if (!"track".equals(text(value, "type"))) return null;
        String key = text(value, "ratingKey"), title = text(value, "title");
        if (blank(key) || blank(title)) return null;
        return new QueueTrack(key, title, text(value, "grandparentTitle"), text(value, "parentTitle"), number(value, "duration"));
    }

    private QueueTrack selectedQueueTrack(JsonObject resultContainer, JsonElement element) {
        if (!element.isJsonObject() || !belongsToSelectedLibrary(resultContainer, element.getAsJsonObject())) return null;
        return queueTrack(element);
    }

    private void ensureSelectedItem(String key) throws IOException, InterruptedException {
        ensureLibrary();
        JsonObject root = getJson("/library/metadata/" + encodePath(key), "");
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        if (metadata.size() == 0 || !metadata.get(0).isJsonObject()
                || !belongsToSelectedLibrary(resultContainer, metadata.get(0).getAsJsonObject())) {
            throw new PlexException(PlexException.Kind.NOT_FOUND,
                    "Plex item is outside the selected music library");
        }
    }

    private boolean playlistBelongsToSelectedLibrary(String key) throws IOException, InterruptedException {
        if (blank(key)) return false;
        JsonObject root = getJson("/playlists/" + encodePath(key) + "/items",
                "X-Plex-Container-Start=0&X-Plex-Container-Size=" + MAX_EXPANDED_TRACKS);
        JsonObject resultContainer = container(root);
        JsonArray metadata = array(resultContainer, "Metadata");
        if (metadata.size() == 0 || number(resultContainer, "totalSize") > MAX_EXPANDED_TRACKS) return false;
        for (JsonElement element : metadata) {
            if (selectedQueueTrack(resultContainer, element) == null) return false;
        }
        return true;
    }

    private boolean belongsToSelectedLibrary(JsonObject resultContainer, JsonObject item) {
        String section = text(item, "librarySectionID");
        if (blank(section)) section = text(resultContainer, "librarySectionID");
        if (!blank(section)) return libraryKey.equals(section);
        String sectionKey = text(item, "librarySectionKey");
        if (blank(sectionKey)) sectionKey = text(resultContainer, "librarySectionKey");
        return !blank(sectionKey) && (sectionKey.equals(libraryKey)
                || sectionKey.endsWith("/library/sections/" + libraryKey));
    }

    private void ensureLibrary() throws IOException, InterruptedException { if (libraryKey == null) validate(); }
    private JsonObject getJson(String path, String query) throws IOException, InterruptedException {
        HttpTransport.Response response;
        try { response = open("GET", path, query); }
        catch (SocketTimeoutException e) { throw new PlexException(PlexException.Kind.OFFLINE, "Plex request timed out", e); }
        try (HttpTransport.Response closeable = response) {
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
            JsonElement parsed = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
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
        try (HttpTransport.Response closeable = response) {
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
            JsonElement parsed = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("root is not an object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) { throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned malformed station JSON", malformed); }
    }

    private HttpTransport.Response open(String method, String path, String query) throws IOException {
        String separator = blank(query) ? "" : "?" + query;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-Plex-Token", token());
        headers.put("X-Plex-Product", "Jammarr");
        headers.put("X-Plex-Version", "1.1.0");
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
    private static String encode(String value) { return urlEncode(value); }
    private static String encodePath(String value) { return encode(value).replace("+", "%20"); }
    private static boolean supportsSonic(String version) {
        if (blank(version)) return true;
        String[] parts = version.split("\\.");
        try { int major = Integer.parseInt(parts[0]), minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0; return major > 1 || major == 1 && minor >= 24; }
        catch (NumberFormatException ignored) { return true; }
    }
    private String baseUrl() { String value = configuredUrl.get().trim(); return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private void validateBaseUrl() throws PlexException {
        String value = baseUrl();
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || blank(uri.getHost())) {
                throw new IllegalArgumentException("unsupported scheme or missing host");
            }
        } catch (IllegalArgumentException invalid) {
            throw new PlexException(PlexException.Kind.CONFIGURATION, "Plex URL must be an http(s) URL with a host", invalid);
        }
    }
    private String token() { return configuredToken.get().trim(); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean containsKey(List<QueueTrack> tracks, String key) {
        for (QueueTrack track : tracks) if (track.key().equals(key)) return true;
        return false;
    }
    private static List<QueueTrack> distinct(List<QueueTrack> tracks, int limit) {
        LinkedHashMap<String, QueueTrack> values = new LinkedHashMap<String, QueueTrack>();
        for (QueueTrack track : tracks) {
            if (!values.containsKey(track.key())) values.put(track.key(), track);
            if (values.size() >= limit) break;
        }
        return immutable(new ArrayList<QueueTrack>(values.values()));
    }
    private static List<QueueTrack> first(List<QueueTrack> tracks, int limit) {
        return immutable(new ArrayList<QueueTrack>(tracks.subList(0, Math.min(tracks.size(), Math.max(0, limit)))));
    }
    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
    private static String urlEncode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

}
