package stonytark.jammarr.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.handler.codec.DecoderException;
import stonytark.jammarr.Jammarr;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JammarrPayloads {
    private static final int MAX_BROWSE_RESULTS = 50;
    private static final int MAX_STATION_SEEDS = 5;
    // One generated current track, the 500-entry manual queue, and three preview rows.
    private static final int MAX_PLAYBACK_ENTRIES = 504;
    private static final int MAX_STATION_PREVIEW = 3;
    private static final int MAX_ADVENTURE_PATH = 100;
    public enum BrowseKind { SEARCH, ARTISTS, ALBUMS, PLAYLISTS, QUEUE }
    public enum ItemKind { TRACK, ARTIST, ALBUM, PLAYLIST }
    public enum ControlAction { PAUSE, RESUME, SKIP, CLEAR, REMOVE, MOVE_UP, MOVE_DOWN }
    public enum PlaybackStatus { IDLE, PREPARING, PLAYING, PAUSED, PLEX_OFFLINE }
    public enum ErrorCode { INVALID_REQUEST, PERMISSION_DENIED, RATE_LIMITED, QUEUE_FULL, PLEX_OFFLINE, TRACK_FAILED, INTERNAL }
    public enum PlaybackOrigin { NONE, MANUAL, STATION, ADVENTURE }
    public enum StationType { NONE, AUTOPLAY, LIBRARY_SHUFFLE, TRACK_RADIO, ARTIST_RADIO, ALBUM_RADIO, SONIC_MIX, SONIC_ADVENTURE }
    public enum SonicCapability { CHECKING, READY, NO_PLEX_PASS, ANALYSIS_INCOMPLETE, UNSUPPORTED, PLEX_OFFLINE }
    public enum StationAction { START, START_NOW, STOP, SET_AUTOPLAY, PREVIEW_ADVENTURE }

    public record MediaItem(ItemKind kind, String key, String title, String subtitle, long durationMs) {
        static MediaItem read(RegistryFriendlyByteBuf buf) {
            return new MediaItem(buf.readEnum(ItemKind.class), buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readVarLong());
        }
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeEnum(kind); buf.writeUtf(key, 256); buf.writeUtf(title, 256); buf.writeUtf(subtitle, 256); buf.writeVarLong(durationMs);
        }
    }

    public record QueueEntry(String key, String title, String artist, long durationMs, PlaybackOrigin source, boolean editable) {
        public QueueEntry(String key, String title, String artist, long durationMs) {
            this(key, title, artist, durationMs, PlaybackOrigin.MANUAL, true);
        }
        static QueueEntry read(RegistryFriendlyByteBuf buf) {
            return new QueueEntry(buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readVarLong(), buf.readEnum(PlaybackOrigin.class), buf.readBoolean());
        }
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(key, 256); buf.writeUtf(title, 256); buf.writeUtf(artist, 256); buf.writeVarLong(durationMs); buf.writeEnum(source); buf.writeBoolean(editable);
        }
    }

    public record StationSeed(ItemKind kind, String key, String title, String subtitle) {
        static StationSeed read(RegistryFriendlyByteBuf buf) {
            return new StationSeed(buf.readEnum(ItemKind.class), buf.readUtf(256), buf.readUtf(256), buf.readUtf(256));
        }
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeEnum(kind); buf.writeUtf(key, 256); buf.writeUtf(title, 256); buf.writeUtf(subtitle, 256);
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> genericType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Jammarr.MODID, path));
    }

    public record OpenScreen() implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE = genericType("open_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> CODEC = StreamCodec.unit(new OpenScreen());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClientHello(int protocolVersion) implements CustomPacketPayload {
        public static final Type<ClientHello> TYPE = genericType("client_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientHello> CODEC = StreamCodec.ofMember(ClientHello::write, ClientHello::read);
        private static ClientHello read(RegistryFriendlyByteBuf b) { return new ClientHello(b.readVarInt()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeVarInt(protocolVersion); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ServerHello(int protocolVersion, long serverEpochMs) implements CustomPacketPayload {
        public static final Type<ServerHello> TYPE = genericType("server_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerHello> CODEC = StreamCodec.ofMember(ServerHello::write, ServerHello::read);
        private static ServerHello read(RegistryFriendlyByteBuf b) { return new ServerHello(b.readVarInt(), b.readLong()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeVarInt(protocolVersion); b.writeLong(serverEpochMs); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TimeSyncRequest(long nonce, long clientSentEpochMs) implements CustomPacketPayload {
        public static final Type<TimeSyncRequest> TYPE = genericType("time_sync_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TimeSyncRequest> CODEC = StreamCodec.ofMember(TimeSyncRequest::write, TimeSyncRequest::read);
        private static TimeSyncRequest read(RegistryFriendlyByteBuf b) { return new TimeSyncRequest(b.readVarLong(), b.readLong()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeVarLong(nonce); b.writeLong(clientSentEpochMs); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TimeSyncResponse(long nonce, long clientSentEpochMs, long serverEpochMs) implements CustomPacketPayload {
        public static final Type<TimeSyncResponse> TYPE = genericType("time_sync_response");
        public static final StreamCodec<RegistryFriendlyByteBuf, TimeSyncResponse> CODEC = StreamCodec.ofMember(TimeSyncResponse::write, TimeSyncResponse::read);
        private static TimeSyncResponse read(RegistryFriendlyByteBuf b) { return new TimeSyncResponse(b.readVarLong(), b.readLong(), b.readLong()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeVarLong(nonce); b.writeLong(clientSentEpochMs); b.writeLong(serverEpochMs); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BrowseRequest(BrowseKind kind, String query, int page) implements CustomPacketPayload {
        public static final Type<BrowseRequest> TYPE = genericType("browse_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseRequest> CODEC = StreamCodec.ofMember(BrowseRequest::write, BrowseRequest::read);
        private static BrowseRequest read(RegistryFriendlyByteBuf b) { return new BrowseRequest(b.readEnum(BrowseKind.class), b.readUtf(128), b.readVarInt()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeEnum(kind); b.writeUtf(query, 128); b.writeVarInt(page); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BrowseResults(BrowseKind kind, String query, int page, boolean hasMore, List<MediaItem> items) implements CustomPacketPayload {
        public static final Type<BrowseResults> TYPE = genericType("browse_results");
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseResults> CODEC = StreamCodec.ofMember(BrowseResults::write, BrowseResults::read);
        private static BrowseResults read(RegistryFriendlyByteBuf b) {
            BrowseKind kind = b.readEnum(BrowseKind.class); String query = b.readUtf(128); int page = b.readVarInt(); boolean more = b.readBoolean();
            int size = boundedCount(b, MAX_BROWSE_RESULTS, "browse results"); List<MediaItem> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) items.add(MediaItem.read(b));
            return new BrowseResults(kind, query, page, more, List.copyOf(items));
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeEnum(kind); b.writeUtf(query, 128); b.writeVarInt(page); b.writeBoolean(hasMore);
            b.writeVarInt(Math.min(MAX_BROWSE_RESULTS, items.size())); items.stream().limit(MAX_BROWSE_RESULTS).forEach(i -> i.write(b));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record QueueRequest(ItemKind kind, String key) implements CustomPacketPayload {
        public static final Type<QueueRequest> TYPE = genericType("queue_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, QueueRequest> CODEC = StreamCodec.ofMember(QueueRequest::write, QueueRequest::read);
        private static QueueRequest read(RegistryFriendlyByteBuf b) { return new QueueRequest(b.readEnum(ItemKind.class), b.readUtf(256)); }
        private void write(RegistryFriendlyByteBuf b) { b.writeEnum(kind); b.writeUtf(key, 256); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ControlRequest(ControlAction action, int index, String expectedKey) implements CustomPacketPayload {
        public static final Type<ControlRequest> TYPE = genericType("control_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ControlRequest> CODEC = StreamCodec.ofMember(ControlRequest::write, ControlRequest::read);
        public ControlRequest(ControlAction action, int index) { this(action, index, ""); }
        private static ControlRequest read(RegistryFriendlyByteBuf b) { return new ControlRequest(b.readEnum(ControlAction.class), b.readVarInt(), b.readUtf(256)); }
        private void write(RegistryFriendlyByteBuf b) { b.writeEnum(action); b.writeVarInt(index); b.writeUtf(expectedKey, 256); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StationRequest(StationAction action, StationType stationType, boolean enabled, long expectedGeneration,
                                 List<StationSeed> seeds) implements CustomPacketPayload {
        public static final Type<StationRequest> TYPE = genericType("station_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, StationRequest> CODEC = StreamCodec.ofMember(StationRequest::write, StationRequest::read);
        private static StationRequest read(RegistryFriendlyByteBuf b) {
            StationAction action = b.readEnum(StationAction.class); StationType type = b.readEnum(StationType.class);
            boolean enabled = b.readBoolean(); long generation = b.readVarLong();
            int count = boundedCount(b, MAX_STATION_SEEDS, "station seeds"); List<StationSeed> seeds = new ArrayList<>(count);
            for (int i = 0; i < count; i++) seeds.add(StationSeed.read(b));
            return new StationRequest(action, type, enabled, generation, List.copyOf(seeds));
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeEnum(action); b.writeEnum(stationType); b.writeBoolean(enabled); b.writeVarLong(expectedGeneration);
            b.writeVarInt(Math.min(MAX_STATION_SEEDS, seeds.size())); seeds.stream().limit(MAX_STATION_SEEDS).forEach(seed -> seed.write(b));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChunkRequest(UUID sessionId, long requestId, int startIndex, int count) implements CustomPacketPayload {
        public static final Type<ChunkRequest> TYPE = genericType("chunk_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ChunkRequest> CODEC = StreamCodec.ofMember(ChunkRequest::write, ChunkRequest::read);
        private static ChunkRequest read(RegistryFriendlyByteBuf b) { return new ChunkRequest(b.readUUID(), b.readVarLong(), b.readVarInt(), b.readVarInt()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeUUID(sessionId); b.writeVarLong(requestId); b.writeVarInt(startIndex); b.writeVarInt(count); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChunkAcknowledgement(UUID sessionId, long requestId, int receivedThroughIndex, long bufferedMs) implements CustomPacketPayload {
        public static final Type<ChunkAcknowledgement> TYPE = genericType("chunk_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf, ChunkAcknowledgement> CODEC = StreamCodec.ofMember(ChunkAcknowledgement::write, ChunkAcknowledgement::read);
        private static ChunkAcknowledgement read(RegistryFriendlyByteBuf b) { return new ChunkAcknowledgement(b.readUUID(), b.readVarLong(), b.readVarInt(), b.readVarLong()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeUUID(sessionId); b.writeVarLong(requestId); b.writeVarInt(receivedThroughIndex); b.writeVarLong(bufferedMs); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AudioHealth(UUID sessionId, String state, int recoveryAttempts, int underruns, int receivedChunks, long bufferedMs) implements CustomPacketPayload {
        public static final Type<AudioHealth> TYPE = genericType("audio_health");
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioHealth> CODEC = StreamCodec.ofMember(AudioHealth::write, AudioHealth::read);
        private static AudioHealth read(RegistryFriendlyByteBuf b) {
            return new AudioHealth(b.readUUID(), b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong());
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(sessionId); b.writeUtf(state, 32); b.writeVarInt(recoveryAttempts); b.writeVarInt(underruns); b.writeVarInt(receivedChunks); b.writeVarLong(bufferedMs);
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ManifestRequest(boolean forceRebuffer) implements CustomPacketPayload {
        public static final Type<ManifestRequest> TYPE = genericType("manifest_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ManifestRequest> CODEC = StreamCodec.ofMember(ManifestRequest::write, ManifestRequest::read);
        private static ManifestRequest read(RegistryFriendlyByteBuf b) { return new ManifestRequest(b.readBoolean()); }
        private void write(RegistryFriendlyByteBuf b) { b.writeBoolean(forceRebuffer); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AudioManifest(UUID sessionId, String title, String artist, int totalChunks, int firstChunk, long durationMs,
                                long startedAtEpochMs, boolean paused, long pausedPositionMs, String sha256) implements CustomPacketPayload {
        public static final Type<AudioManifest> TYPE = genericType("audio_manifest");
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioManifest> CODEC = StreamCodec.ofMember(AudioManifest::write, AudioManifest::read);
        private static AudioManifest read(RegistryFriendlyByteBuf b) {
            return new AudioManifest(b.readUUID(), b.readUtf(256), b.readUtf(256), b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readLong(), b.readBoolean(), b.readVarLong(), b.readUtf(64));
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(sessionId); b.writeUtf(title, 256); b.writeUtf(artist, 256); b.writeVarInt(totalChunks); b.writeVarInt(firstChunk); b.writeVarLong(durationMs); b.writeLong(startedAtEpochMs); b.writeBoolean(paused); b.writeVarLong(pausedPositionMs); b.writeUtf(sha256, 64);
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AudioChunk(UUID sessionId, long requestId, int index, long startMs, String sha256, byte[] data) implements CustomPacketPayload {
        public static final Type<AudioChunk> TYPE = genericType("audio_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioChunk> CODEC = StreamCodec.ofMember(AudioChunk::write, AudioChunk::read);
        private static AudioChunk read(RegistryFriendlyByteBuf b) { return new AudioChunk(b.readUUID(), b.readVarLong(), b.readVarInt(), b.readVarLong(), b.readUtf(64), b.readByteArray(16_384)); }
        private void write(RegistryFriendlyByteBuf b) { b.writeUUID(sessionId); b.writeVarLong(requestId); b.writeVarInt(index); b.writeVarLong(startMs); b.writeUtf(sha256, 64); b.writeByteArray(data); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                                long positionMs, long durationMs, long serverEpochMs, boolean operator,
                                PlaybackOrigin origin, String sourceName, List<QueueEntry> queue) implements CustomPacketPayload {
        public PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                             long positionMs, long durationMs, long serverEpochMs, boolean operator, List<QueueEntry> queue) {
            this(status, statusMessage, title, artist, paused, positionMs, durationMs, serverEpochMs, operator, PlaybackOrigin.NONE, "", queue);
        }
        public PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                             long positionMs, long durationMs, long serverEpochMs, boolean operator,
                             PlaybackOrigin origin, List<QueueEntry> queue) {
            this(status, statusMessage, title, artist, paused, positionMs, durationMs, serverEpochMs, operator, origin, "", queue);
        }
        public static final Type<PlaybackState> TYPE = genericType("playback_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaybackState> CODEC = StreamCodec.ofMember(PlaybackState::write, PlaybackState::read);
        private static PlaybackState read(RegistryFriendlyByteBuf b) {
            PlaybackStatus status = b.readEnum(PlaybackStatus.class); String statusMessage = b.readUtf(256), title = b.readUtf(256), artist = b.readUtf(256);
            boolean paused = b.readBoolean(); long pos = b.readVarLong(), duration = b.readVarLong(), serverTime = b.readLong(); boolean op = b.readBoolean();
            PlaybackOrigin origin = b.readEnum(PlaybackOrigin.class); String sourceName = b.readUtf(256);
            int size = boundedCount(b, MAX_PLAYBACK_ENTRIES, "playback entries"); List<QueueEntry> queue = new ArrayList<>(size);
            for (int i = 0; i < size; i++) queue.add(QueueEntry.read(b));
            return new PlaybackState(status, statusMessage, title, artist, paused, pos, duration, serverTime, op, origin, sourceName, List.copyOf(queue));
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeEnum(status); b.writeUtf(statusMessage, 256); b.writeUtf(title, 256); b.writeUtf(artist, 256); b.writeBoolean(paused);
            b.writeVarLong(positionMs); b.writeVarLong(durationMs); b.writeLong(serverEpochMs); b.writeBoolean(operator); b.writeEnum(origin); b.writeUtf(sourceName, 256);
            b.writeVarInt(Math.min(MAX_PLAYBACK_ENTRIES, queue.size())); queue.stream().limit(MAX_PLAYBACK_ENTRIES).forEach(q -> q.write(b));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StationState(StationType stationType, boolean active, boolean autoplayEnabled, long generation,
                               SonicCapability capability, String capabilityMessage, String name,
                               List<StationSeed> seeds, List<QueueEntry> preview) implements CustomPacketPayload {
        public static final Type<StationState> TYPE = genericType("station_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, StationState> CODEC = StreamCodec.ofMember(StationState::write, StationState::read);
        private static StationState read(RegistryFriendlyByteBuf b) {
            StationType type = b.readEnum(StationType.class); boolean active = b.readBoolean(); boolean autoplay = b.readBoolean();
            long generation = b.readVarLong(); SonicCapability capability = b.readEnum(SonicCapability.class);
            String message = b.readUtf(256), name = b.readUtf(256);
            int seedCount = boundedCount(b, MAX_STATION_SEEDS, "station seeds"); List<StationSeed> seeds = new ArrayList<>(seedCount);
            for (int i = 0; i < seedCount; i++) seeds.add(StationSeed.read(b));
            int previewCount = boundedCount(b, MAX_STATION_PREVIEW, "station preview"); List<QueueEntry> preview = new ArrayList<>(previewCount);
            for (int i = 0; i < previewCount; i++) preview.add(QueueEntry.read(b));
            return new StationState(type, active, autoplay, generation, capability, message, name, List.copyOf(seeds), List.copyOf(preview));
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeEnum(stationType); b.writeBoolean(active); b.writeBoolean(autoplayEnabled); b.writeVarLong(generation);
            b.writeEnum(capability); b.writeUtf(capabilityMessage, 256); b.writeUtf(name, 256);
            b.writeVarInt(Math.min(MAX_STATION_SEEDS, seeds.size())); seeds.stream().limit(MAX_STATION_SEEDS).forEach(seed -> seed.write(b));
            b.writeVarInt(Math.min(MAX_STATION_PREVIEW, preview.size())); preview.stream().limit(MAX_STATION_PREVIEW).forEach(entry -> entry.write(b));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AdventurePreview(long generation, String message, List<QueueEntry> path) implements CustomPacketPayload {
        public static final Type<AdventurePreview> TYPE = genericType("adventure_preview");
        public static final StreamCodec<RegistryFriendlyByteBuf, AdventurePreview> CODEC = StreamCodec.ofMember(AdventurePreview::write, AdventurePreview::read);
        private static AdventurePreview read(RegistryFriendlyByteBuf b) {
            long generation = b.readVarLong(); String message = b.readUtf(256);
            int count = boundedCount(b, MAX_ADVENTURE_PATH, "Adventure path"); List<QueueEntry> path = new ArrayList<>(count);
            for (int i = 0; i < count; i++) path.add(QueueEntry.read(b));
            return new AdventurePreview(generation, message, List.copyOf(path));
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeVarLong(generation); b.writeUtf(message, 256); b.writeVarInt(Math.min(MAX_ADVENTURE_PATH, path.size())); path.stream().limit(MAX_ADVENTURE_PATH).forEach(entry -> entry.write(b));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ErrorMessage(ErrorCode code, String message) implements CustomPacketPayload {
        public static final Type<ErrorMessage> TYPE = genericType("error");
        public static final StreamCodec<RegistryFriendlyByteBuf, ErrorMessage> CODEC = StreamCodec.ofMember(ErrorMessage::write, ErrorMessage::read);
        private static ErrorMessage read(RegistryFriendlyByteBuf b) { return new ErrorMessage(b.readEnum(ErrorCode.class), b.readUtf(512)); }
        private void write(RegistryFriendlyByteBuf b) { b.writeEnum(code); b.writeUtf(message, 512); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static int boundedCount(RegistryFriendlyByteBuf buffer, int maximum, String field) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Jammarr " + field + " count exceeds " + maximum);
        return count;
    }

    private JammarrPayloads() {}
}
