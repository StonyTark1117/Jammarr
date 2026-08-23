package stonytark.jammarr.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.handler.codec.DecoderException;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.core.protocol.WireCodec;
import java.util.List;
import java.util.UUID;

public final class JammarrPayloads {
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
            StationModels.MediaItem value = decode(ControlPackets.MEDIA_ITEM, buf);
            return new MediaItem(enumValue(ItemKind.class, value.kind()), value.key(), value.title(), value.subtitle(), value.durationMs());
        }
        void write(RegistryFriendlyByteBuf buf) {
            ControlPackets.MEDIA_ITEM.encode(new MinecraftWireOutput(buf), new StationModels.MediaItem(
                    enumValue(StationModels.ItemKind.class, kind), key, title, subtitle, durationMs));
        }
    }

    public record QueueEntry(String key, String title, String artist, long durationMs, PlaybackOrigin source, boolean editable) {
        public QueueEntry(String key, String title, String artist, long durationMs) {
            this(key, title, artist, durationMs, PlaybackOrigin.MANUAL, true);
        }
        static QueueEntry read(RegistryFriendlyByteBuf buf) {
            return toPayload(decode(StatePackets.QUEUE_ENTRY, buf));
        }
        void write(RegistryFriendlyByteBuf buf) {
            StatePackets.QUEUE_ENTRY.encode(new MinecraftWireOutput(buf), toCore(this));
        }
    }

    public record StationSeed(ItemKind kind, String key, String title, String subtitle) {
        static StationSeed read(RegistryFriendlyByteBuf buf) {
            StationModels.StationSeed value = decode(ControlPackets.STATION_SEED, buf);
            return new StationSeed(enumValue(ItemKind.class, value.kind()), value.key(), value.title(), value.subtitle());
        }
        void write(RegistryFriendlyByteBuf buf) {
            ControlPackets.STATION_SEED.encode(new MinecraftWireOutput(buf), new StationModels.StationSeed(
                    enumValue(StationModels.ItemKind.class, kind), key, title, subtitle));
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> genericType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Jammarr.MODID, path));
    }

    public record OpenScreen() implements CustomPacketPayload, JammarrMessage {
        public static final Type<OpenScreen> TYPE = genericType("open_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> CODEC = StreamCodec.unit(new OpenScreen());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClientHello(int protocolVersion) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ClientHello> TYPE = genericType("client_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientHello> CODEC = StreamCodec.ofMember(ClientHello::write, ClientHello::read);
        private static ClientHello read(RegistryFriendlyByteBuf b) {
            return new ClientHello(decode(ControlPackets.CLIENT_HELLO, b).protocolVersion());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.CLIENT_HELLO.encode(new MinecraftWireOutput(b), new ControlPackets.ClientHello(protocolVersion));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ServerHello(int protocolVersion, long serverEpochMs) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ServerHello> TYPE = genericType("server_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerHello> CODEC = StreamCodec.ofMember(ServerHello::write, ServerHello::read);
        private static ServerHello read(RegistryFriendlyByteBuf b) {
            ControlPackets.ServerHello value = decode(ControlPackets.SERVER_HELLO, b);
            return new ServerHello(value.protocolVersion(), value.serverEpochMs());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.SERVER_HELLO.encode(new MinecraftWireOutput(b), new ControlPackets.ServerHello(protocolVersion, serverEpochMs));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TimeSyncRequest(long nonce, long clientSentEpochMs) implements CustomPacketPayload, JammarrMessage {
        public static final Type<TimeSyncRequest> TYPE = genericType("time_sync_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TimeSyncRequest> CODEC = StreamCodec.ofMember(TimeSyncRequest::write, TimeSyncRequest::read);
        private static TimeSyncRequest read(RegistryFriendlyByteBuf b) {
            ControlPackets.TimeSyncRequest value = decode(ControlPackets.TIME_SYNC_REQUEST, b);
            return new TimeSyncRequest(value.nonce(), value.clientSentEpochMs());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.TIME_SYNC_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.TimeSyncRequest(nonce, clientSentEpochMs));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TimeSyncResponse(long nonce, long clientSentEpochMs, long serverEpochMs) implements CustomPacketPayload, JammarrMessage {
        public static final Type<TimeSyncResponse> TYPE = genericType("time_sync_response");
        public static final StreamCodec<RegistryFriendlyByteBuf, TimeSyncResponse> CODEC = StreamCodec.ofMember(TimeSyncResponse::write, TimeSyncResponse::read);
        private static TimeSyncResponse read(RegistryFriendlyByteBuf b) {
            ControlPackets.TimeSyncResponse value = decode(ControlPackets.TIME_SYNC_RESPONSE, b);
            return new TimeSyncResponse(value.nonce(), value.clientSentEpochMs(), value.serverEpochMs());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.TIME_SYNC_RESPONSE.encode(new MinecraftWireOutput(b), new ControlPackets.TimeSyncResponse(nonce, clientSentEpochMs, serverEpochMs));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BrowseRequest(BrowseKind kind, String query, int page) implements CustomPacketPayload, JammarrMessage {
        public static final Type<BrowseRequest> TYPE = genericType("browse_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseRequest> CODEC = StreamCodec.ofMember(BrowseRequest::write, BrowseRequest::read);
        private static BrowseRequest read(RegistryFriendlyByteBuf b) {
            ControlPackets.BrowseRequest value = decode(ControlPackets.BROWSE_REQUEST, b);
            return new BrowseRequest(enumValue(BrowseKind.class, value.kind()), value.query(), value.page());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.BROWSE_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.BrowseRequest(
                    enumValue(ControlPackets.BrowseKind.class, kind), query, page));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BrowseResults(BrowseKind kind, String query, int page, boolean hasMore, List<MediaItem> items) implements CustomPacketPayload, JammarrMessage {
        public static final Type<BrowseResults> TYPE = genericType("browse_results");
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseResults> CODEC = StreamCodec.ofMember(BrowseResults::write, BrowseResults::read);
        private static BrowseResults read(RegistryFriendlyByteBuf b) {
            ControlPackets.BrowseResults value = decode(ControlPackets.BROWSE_RESULTS, b);
            List<MediaItem> items = value.items().stream().map(JammarrPayloads::toPayload).toList();
            return new BrowseResults(enumValue(BrowseKind.class, value.kind()), value.query(), value.page(), value.hasMore(), items);
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.BROWSE_RESULTS.encode(new MinecraftWireOutput(b), new ControlPackets.BrowseResults(
                    enumValue(ControlPackets.BrowseKind.class, kind), query, page, hasMore,
                    items.stream().map(JammarrPayloads::toCore).toList()));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record QueueRequest(ItemKind kind, String key) implements CustomPacketPayload, JammarrMessage {
        public static final Type<QueueRequest> TYPE = genericType("queue_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, QueueRequest> CODEC = StreamCodec.ofMember(QueueRequest::write, QueueRequest::read);
        private static QueueRequest read(RegistryFriendlyByteBuf b) {
            ControlPackets.QueueRequest value = decode(ControlPackets.QUEUE_REQUEST, b);
            return new QueueRequest(enumValue(ItemKind.class, value.kind()), value.key());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.QUEUE_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.QueueRequest(
                    enumValue(StationModels.ItemKind.class, kind), key));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ControlRequest(ControlAction action, int index, String expectedKey) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ControlRequest> TYPE = genericType("control_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ControlRequest> CODEC = StreamCodec.ofMember(ControlRequest::write, ControlRequest::read);
        public ControlRequest(ControlAction action, int index) { this(action, index, ""); }
        private static ControlRequest read(RegistryFriendlyByteBuf b) {
            ControlPackets.ControlRequest value = decode(ControlPackets.CONTROL_REQUEST, b);
            return new ControlRequest(enumValue(ControlAction.class, value.action()), value.index(), value.expectedKey());
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.CONTROL_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.ControlRequest(
                    enumValue(ControlPackets.ControlAction.class, action), index, expectedKey));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StationRequest(StationAction action, StationType stationType, boolean enabled, long expectedGeneration,
                                 List<StationSeed> seeds) implements CustomPacketPayload, JammarrMessage {
        public static final Type<StationRequest> TYPE = genericType("station_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, StationRequest> CODEC = StreamCodec.ofMember(StationRequest::write, StationRequest::read);
        private static StationRequest read(RegistryFriendlyByteBuf b) {
            ControlPackets.StationRequest value = decode(ControlPackets.STATION_REQUEST, b);
            List<StationSeed> seeds = value.seeds().stream().map(JammarrPayloads::toPayload).toList();
            return new StationRequest(enumValue(StationAction.class, value.action()),
                    enumValue(StationType.class, value.stationType()), value.enabled(), value.expectedGeneration(), seeds);
        }
        private void write(RegistryFriendlyByteBuf b) {
            ControlPackets.STATION_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.StationRequest(
                    enumValue(ControlPackets.StationAction.class, action), enumValue(StationModels.StationType.class, stationType),
                    enabled, expectedGeneration, seeds.stream().map(JammarrPayloads::toCore).toList()));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChunkRequest(UUID sessionId, long requestId, int startIndex, int count) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ChunkRequest> TYPE = genericType("chunk_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ChunkRequest> CODEC = StreamCodec.ofMember(ChunkRequest::write, ChunkRequest::read);
        private static ChunkRequest read(RegistryFriendlyByteBuf b) {
            TransportPackets.ChunkRequest value = decode(TransportPackets.CHUNK_REQUEST, b);
            return new ChunkRequest(value.sessionId(), value.requestId(), value.startIndex(), value.count());
        }
        private void write(RegistryFriendlyByteBuf b) {
            TransportPackets.CHUNK_REQUEST.encode(new MinecraftWireOutput(b), new TransportPackets.ChunkRequest(sessionId, requestId, startIndex, count));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChunkAcknowledgement(UUID sessionId, long requestId, int receivedThroughIndex, long bufferedMs) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ChunkAcknowledgement> TYPE = genericType("chunk_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf, ChunkAcknowledgement> CODEC = StreamCodec.ofMember(ChunkAcknowledgement::write, ChunkAcknowledgement::read);
        private static ChunkAcknowledgement read(RegistryFriendlyByteBuf b) {
            TransportPackets.ChunkAcknowledgement value = decode(TransportPackets.CHUNK_ACKNOWLEDGEMENT, b);
            return new ChunkAcknowledgement(value.sessionId(), value.requestId(), value.receivedThroughIndex(), value.bufferedMs());
        }
        private void write(RegistryFriendlyByteBuf b) {
            TransportPackets.CHUNK_ACKNOWLEDGEMENT.encode(new MinecraftWireOutput(b),
                    new TransportPackets.ChunkAcknowledgement(sessionId, requestId, receivedThroughIndex, bufferedMs));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AudioHealth(UUID sessionId, String state, int recoveryAttempts, int underruns, int receivedChunks, long bufferedMs) implements CustomPacketPayload, JammarrMessage {
        public static final Type<AudioHealth> TYPE = genericType("audio_health");
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioHealth> CODEC = StreamCodec.ofMember(AudioHealth::write, AudioHealth::read);
        private static AudioHealth read(RegistryFriendlyByteBuf b) {
            StatePackets.AudioHealth value = decode(StatePackets.AUDIO_HEALTH, b);
            return new AudioHealth(value.sessionId(), value.state(), value.recoveryAttempts(), value.underruns(),
                    value.receivedChunks(), value.bufferedMs());
        }
        private void write(RegistryFriendlyByteBuf b) {
            StatePackets.AUDIO_HEALTH.encode(new MinecraftWireOutput(b), new StatePackets.AudioHealth(
                    sessionId, state, recoveryAttempts, underruns, receivedChunks, bufferedMs));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ManifestRequest(boolean forceRebuffer) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ManifestRequest> TYPE = genericType("manifest_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ManifestRequest> CODEC = StreamCodec.ofMember(ManifestRequest::write, ManifestRequest::read);
        private static ManifestRequest read(RegistryFriendlyByteBuf b) {
            return new ManifestRequest(decode(StatePackets.MANIFEST_REQUEST, b).forceRebuffer());
        }
        private void write(RegistryFriendlyByteBuf b) {
            StatePackets.MANIFEST_REQUEST.encode(new MinecraftWireOutput(b), new StatePackets.ManifestRequest(forceRebuffer));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AudioManifest(UUID sessionId, String title, String artist, int totalChunks, int firstChunk, long durationMs,
                                long startedAtEpochMs, boolean paused, long pausedPositionMs, String sha256) implements CustomPacketPayload, JammarrMessage {
        public static final Type<AudioManifest> TYPE = genericType("audio_manifest");
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioManifest> CODEC = StreamCodec.ofMember(AudioManifest::write, AudioManifest::read);
        private static AudioManifest read(RegistryFriendlyByteBuf b) {
            TransportPackets.AudioManifest value = decode(TransportPackets.AUDIO_MANIFEST, b);
            return new AudioManifest(value.sessionId(), value.title(), value.artist(), value.totalChunks(), value.firstChunk(), value.durationMs(),
                    value.startedAtEpochMs(), value.paused(), value.pausedPositionMs(), value.sha256());
        }
        private void write(RegistryFriendlyByteBuf b) {
            TransportPackets.AUDIO_MANIFEST.encode(new MinecraftWireOutput(b), new TransportPackets.AudioManifest(sessionId, title, artist,
                    totalChunks, firstChunk, durationMs, startedAtEpochMs, paused, pausedPositionMs, sha256));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AudioChunk(UUID sessionId, long requestId, int index, long startMs, String sha256, byte[] data) implements CustomPacketPayload, JammarrMessage {
        public static final Type<AudioChunk> TYPE = genericType("audio_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, AudioChunk> CODEC = StreamCodec.ofMember(AudioChunk::write, AudioChunk::read);
        private static AudioChunk read(RegistryFriendlyByteBuf b) {
            TransportPackets.AudioChunk value = decode(TransportPackets.AUDIO_CHUNK, b);
            return new AudioChunk(value.sessionId(), value.requestId(), value.index(), value.startMs(), value.sha256(), value.data());
        }
        private void write(RegistryFriendlyByteBuf b) {
            TransportPackets.AUDIO_CHUNK.encode(new MinecraftWireOutput(b), new TransportPackets.AudioChunk(sessionId, requestId, index, startMs, sha256, data));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                                long positionMs, long durationMs, long serverEpochMs, boolean operator,
                                PlaybackOrigin origin, String sourceName, List<QueueEntry> queue) implements CustomPacketPayload, JammarrMessage {
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
            StatePackets.PlaybackState value = decode(StatePackets.PLAYBACK_STATE, b);
            return new PlaybackState(enumValue(PlaybackStatus.class, value.status()), value.statusMessage(),
                    value.title(), value.artist(), value.paused(), value.positionMs(), value.durationMs(),
                    value.serverEpochMs(), value.operator(), enumValue(PlaybackOrigin.class, value.origin()),
                    value.sourceName(), value.queue().stream().map(JammarrPayloads::toPayload).toList());
        }
        private void write(RegistryFriendlyByteBuf b) {
            StatePackets.PLAYBACK_STATE.encode(new MinecraftWireOutput(b), new StatePackets.PlaybackState(
                    enumValue(StatePackets.PlaybackStatus.class, status), statusMessage, title, artist, paused,
                    positionMs, durationMs, serverEpochMs, operator, enumValue(StatePackets.PlaybackOrigin.class, origin),
                    sourceName, queue.stream().map(JammarrPayloads::toCore).toList()));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StationState(StationType stationType, boolean active, boolean autoplayEnabled, long generation,
                               SonicCapability capability, String capabilityMessage, String name,
                               List<StationSeed> seeds, List<QueueEntry> preview) implements CustomPacketPayload, JammarrMessage {
        public static final Type<StationState> TYPE = genericType("station_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, StationState> CODEC = StreamCodec.ofMember(StationState::write, StationState::read);
        private static StationState read(RegistryFriendlyByteBuf b) {
            StatePackets.StationState value = decode(StatePackets.STATION_STATE, b);
            return new StationState(enumValue(StationType.class, value.stationType()), value.active(),
                    value.autoplayEnabled(), value.generation(), enumValue(SonicCapability.class, value.capability()),
                    value.capabilityMessage(), value.name(), value.seeds().stream().map(JammarrPayloads::toPayload).toList(),
                    value.preview().stream().map(JammarrPayloads::toPayload).toList());
        }
        private void write(RegistryFriendlyByteBuf b) {
            StatePackets.STATION_STATE.encode(new MinecraftWireOutput(b), new StatePackets.StationState(
                    enumValue(StationModels.StationType.class, stationType), active, autoplayEnabled, generation,
                    enumValue(StationModels.SonicCapability.class, capability), capabilityMessage, name,
                    seeds.stream().map(JammarrPayloads::toCore).toList(),
                    preview.stream().map(JammarrPayloads::toCore).toList()));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record AdventurePreview(long generation, String message, List<QueueEntry> path) implements CustomPacketPayload, JammarrMessage {
        public static final Type<AdventurePreview> TYPE = genericType("adventure_preview");
        public static final StreamCodec<RegistryFriendlyByteBuf, AdventurePreview> CODEC = StreamCodec.ofMember(AdventurePreview::write, AdventurePreview::read);
        private static AdventurePreview read(RegistryFriendlyByteBuf b) {
            StatePackets.AdventurePreview value = decode(StatePackets.ADVENTURE_PREVIEW, b);
            return new AdventurePreview(value.generation(), value.message(),
                    value.path().stream().map(JammarrPayloads::toPayload).toList());
        }
        private void write(RegistryFriendlyByteBuf b) {
            StatePackets.ADVENTURE_PREVIEW.encode(new MinecraftWireOutput(b), new StatePackets.AdventurePreview(
                    generation, message, path.stream().map(JammarrPayloads::toCore).toList()));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ErrorMessage(ErrorCode code, String message) implements CustomPacketPayload, JammarrMessage {
        public static final Type<ErrorMessage> TYPE = genericType("error");
        public static final StreamCodec<RegistryFriendlyByteBuf, ErrorMessage> CODEC = StreamCodec.ofMember(ErrorMessage::write, ErrorMessage::read);
        private static ErrorMessage read(RegistryFriendlyByteBuf b) {
            StatePackets.ErrorMessage value = decode(StatePackets.ERROR_MESSAGE, b);
            return new ErrorMessage(enumValue(ErrorCode.class, value.code()), value.message());
        }
        private void write(RegistryFriendlyByteBuf b) {
            StatePackets.ERROR_MESSAGE.encode(new MinecraftWireOutput(b), new StatePackets.ErrorMessage(
                    enumValue(StatePackets.ErrorCode.class, code), message));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static StationModels.MediaItem toCore(MediaItem value) {
        return new StationModels.MediaItem(enumValue(StationModels.ItemKind.class, value.kind()),
                value.key(), value.title(), value.subtitle(), value.durationMs());
    }

    private static MediaItem toPayload(StationModels.MediaItem value) {
        return new MediaItem(enumValue(ItemKind.class, value.kind()), value.key(), value.title(),
                value.subtitle(), value.durationMs());
    }

    private static StationModels.StationSeed toCore(StationSeed value) {
        return new StationModels.StationSeed(enumValue(StationModels.ItemKind.class, value.kind()),
                value.key(), value.title(), value.subtitle());
    }

    private static StationSeed toPayload(StationModels.StationSeed value) {
        return new StationSeed(enumValue(ItemKind.class, value.kind()), value.key(), value.title(), value.subtitle());
    }

    private static StatePackets.QueueEntry toCore(QueueEntry value) {
        return new StatePackets.QueueEntry(value.key(), value.title(), value.artist(), value.durationMs(),
                enumValue(StatePackets.PlaybackOrigin.class, value.source()), value.editable());
    }

    private static QueueEntry toPayload(StatePackets.QueueEntry value) {
        return new QueueEntry(value.key(), value.title(), value.artist(), value.durationMs(),
                enumValue(PlaybackOrigin.class, value.source()), value.editable());
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, Enum<?> value) {
        return Enum.valueOf(type, value.name());
    }

    private static <T> T decode(WireCodec<T> codec, RegistryFriendlyByteBuf buffer) {
        try {
            return codec.decode(new MinecraftWireInput(buffer));
        } catch (ProtocolException malformed) {
            throw new DecoderException(malformed.getMessage(), malformed);
        }
    }

    private JammarrPayloads() {}
}
