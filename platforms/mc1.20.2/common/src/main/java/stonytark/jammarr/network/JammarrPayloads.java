package stonytark.jammarr.network;

import net.minecraft.network.FriendlyByteBuf;
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
        static MediaItem read(FriendlyByteBuf buf) {
            StationModels.MediaItem value = decode(ControlPackets.MEDIA_ITEM, buf);
            return new MediaItem(enumValue(ItemKind.class, value.kind()), value.key(), value.title(), value.subtitle(), value.durationMs());
        }
        void write(FriendlyByteBuf buf) {
            ControlPackets.MEDIA_ITEM.encode(new MinecraftWireOutput(buf), new StationModels.MediaItem(
                    enumValue(StationModels.ItemKind.class, kind), key, title, subtitle, durationMs));
        }
    }

    public record QueueEntry(String key, String title, String artist, long durationMs, PlaybackOrigin source, boolean editable) {
        public QueueEntry(String key, String title, String artist, long durationMs) {
            this(key, title, artist, durationMs, PlaybackOrigin.MANUAL, true);
        }
        static QueueEntry read(FriendlyByteBuf buf) {
            return toPayload(decode(StatePackets.QUEUE_ENTRY, buf));
        }
        void write(FriendlyByteBuf buf) {
            StatePackets.QUEUE_ENTRY.encode(new MinecraftWireOutput(buf), toCore(this));
        }
    }

    public record StationSeed(ItemKind kind, String key, String title, String subtitle) {
        static StationSeed read(FriendlyByteBuf buf) {
            StationModels.StationSeed value = decode(ControlPackets.STATION_SEED, buf);
            return new StationSeed(enumValue(ItemKind.class, value.kind()), value.key(), value.title(), value.subtitle());
        }
        void write(FriendlyByteBuf buf) {
            ControlPackets.STATION_SEED.encode(new MinecraftWireOutput(buf), new StationModels.StationSeed(
                    enumValue(StationModels.ItemKind.class, kind), key, title, subtitle));
        }
    }

    private static ResourceLocation id(String path) { return new ResourceLocation(Jammarr.MODID, path); }

    public record OpenScreen() implements JammarrMessage {
        public static final ResourceLocation ID = id("open_screen");
    }

    public record ClientHello(int protocolVersion, long features, int audioChunkBytes, int chunksPerRequest) implements JammarrMessage {
        public static final ResourceLocation ID = id("client_hello");
        public ClientHello(int protocolVersion) { this(protocolVersion, stonytark.jammarr.core.protocol.ProtocolCapabilities.SUPPORTED_FEATURES, stonytark.jammarr.core.protocol.ProtocolCapabilities.AUDIO_CHUNK_BYTES, stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST); }
        public static ClientHello read(FriendlyByteBuf b) {
            ControlPackets.ClientHello value = decode(ControlPackets.CLIENT_HELLO, b);
            return new ClientHello(value.protocolVersion(), value.features(), value.audioChunkBytes(), value.chunksPerRequest());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.CLIENT_HELLO.encode(new MinecraftWireOutput(b), new ControlPackets.ClientHello(protocolVersion, features, audioChunkBytes, chunksPerRequest));
        }
    }

    public record ServerHello(int protocolVersion, long serverEpochMs, long features, int audioChunkBytes, int chunksPerRequest) implements JammarrMessage {
        public static final ResourceLocation ID = id("server_hello");
        public ServerHello(int protocolVersion, long serverEpochMs) { this(protocolVersion, serverEpochMs, stonytark.jammarr.core.protocol.ProtocolCapabilities.SUPPORTED_FEATURES, stonytark.jammarr.core.protocol.ProtocolCapabilities.AUDIO_CHUNK_BYTES, stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST); }
        public static ServerHello read(FriendlyByteBuf b) {
            ControlPackets.ServerHello value = decode(ControlPackets.SERVER_HELLO, b);
            return new ServerHello(value.protocolVersion(), value.serverEpochMs(), value.features(), value.audioChunkBytes(), value.chunksPerRequest());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.SERVER_HELLO.encode(new MinecraftWireOutput(b), new ControlPackets.ServerHello(protocolVersion, serverEpochMs, features, audioChunkBytes, chunksPerRequest));
        }
    }

    public record TimeSyncRequest(long nonce, long clientSentEpochMs) implements JammarrMessage {
        public static final ResourceLocation ID = id("time_sync_request");
        public static TimeSyncRequest read(FriendlyByteBuf b) {
            ControlPackets.TimeSyncRequest value = decode(ControlPackets.TIME_SYNC_REQUEST, b);
            return new TimeSyncRequest(value.nonce(), value.clientSentEpochMs());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.TIME_SYNC_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.TimeSyncRequest(nonce, clientSentEpochMs));
        }
    }

    public record TimeSyncResponse(long nonce, long clientSentEpochMs, long serverEpochMs) implements JammarrMessage {
        public static final ResourceLocation ID = id("time_sync_response");
        public static TimeSyncResponse read(FriendlyByteBuf b) {
            ControlPackets.TimeSyncResponse value = decode(ControlPackets.TIME_SYNC_RESPONSE, b);
            return new TimeSyncResponse(value.nonce(), value.clientSentEpochMs(), value.serverEpochMs());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.TIME_SYNC_RESPONSE.encode(new MinecraftWireOutput(b), new ControlPackets.TimeSyncResponse(nonce, clientSentEpochMs, serverEpochMs));
        }
    }

    public record BrowseRequest(BrowseKind kind, String query, int page) implements JammarrMessage {
        public static final ResourceLocation ID = id("browse_request");
        public static BrowseRequest read(FriendlyByteBuf b) {
            ControlPackets.BrowseRequest value = decode(ControlPackets.BROWSE_REQUEST, b);
            return new BrowseRequest(enumValue(BrowseKind.class, value.kind()), value.query(), value.page());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.BROWSE_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.BrowseRequest(
                    enumValue(ControlPackets.BrowseKind.class, kind), query, page));
        }
    }

    public record BrowseResults(BrowseKind kind, String query, int page, boolean hasMore, List<MediaItem> items) implements JammarrMessage {
        public static final ResourceLocation ID = id("browse_results");
        public static BrowseResults read(FriendlyByteBuf b) {
            ControlPackets.BrowseResults value = decode(ControlPackets.BROWSE_RESULTS, b);
            List<MediaItem> items = value.items().stream().map(JammarrPayloads::toPayload).toList();
            return new BrowseResults(enumValue(BrowseKind.class, value.kind()), value.query(), value.page(), value.hasMore(), items);
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.BROWSE_RESULTS.encode(new MinecraftWireOutput(b), new ControlPackets.BrowseResults(
                    enumValue(ControlPackets.BrowseKind.class, kind), query, page, hasMore,
                    items.stream().map(JammarrPayloads::toCore).toList()));
        }
    }

    public record QueueRequest(ItemKind kind, String key) implements JammarrMessage {
        public static final ResourceLocation ID = id("queue_request");
        public static QueueRequest read(FriendlyByteBuf b) {
            ControlPackets.QueueRequest value = decode(ControlPackets.QUEUE_REQUEST, b);
            return new QueueRequest(enumValue(ItemKind.class, value.kind()), value.key());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.QUEUE_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.QueueRequest(
                    enumValue(StationModels.ItemKind.class, kind), key));
        }
    }

    public record ControlRequest(ControlAction action, int index, String expectedKey) implements JammarrMessage {
        public static final ResourceLocation ID = id("control_request");
        public ControlRequest(ControlAction action, int index) { this(action, index, ""); }
        public static ControlRequest read(FriendlyByteBuf b) {
            ControlPackets.ControlRequest value = decode(ControlPackets.CONTROL_REQUEST, b);
            return new ControlRequest(enumValue(ControlAction.class, value.action()), value.index(), value.expectedKey());
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.CONTROL_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.ControlRequest(
                    enumValue(ControlPackets.ControlAction.class, action), index, expectedKey));
        }
    }

    public record StationRequest(StationAction action, StationType stationType, boolean enabled, long expectedGeneration,
                                 List<StationSeed> seeds) implements JammarrMessage {
        public static final ResourceLocation ID = id("station_request");
        public static StationRequest read(FriendlyByteBuf b) {
            ControlPackets.StationRequest value = decode(ControlPackets.STATION_REQUEST, b);
            List<StationSeed> seeds = value.seeds().stream().map(JammarrPayloads::toPayload).toList();
            return new StationRequest(enumValue(StationAction.class, value.action()),
                    enumValue(StationType.class, value.stationType()), value.enabled(), value.expectedGeneration(), seeds);
        }
        public void write(FriendlyByteBuf b) {
            ControlPackets.STATION_REQUEST.encode(new MinecraftWireOutput(b), new ControlPackets.StationRequest(
                    enumValue(ControlPackets.StationAction.class, action), enumValue(StationModels.StationType.class, stationType),
                    enabled, expectedGeneration, seeds.stream().map(JammarrPayloads::toCore).toList()));
        }
    }

    public record ChunkRequest(UUID sessionId, long requestId, int startIndex, int count) implements JammarrMessage {
        public static final ResourceLocation ID = id("chunk_request");
        public static ChunkRequest read(FriendlyByteBuf b) {
            TransportPackets.ChunkRequest value = decode(TransportPackets.CHUNK_REQUEST, b);
            return new ChunkRequest(value.sessionId(), value.requestId(), value.startIndex(), value.count());
        }
        public void write(FriendlyByteBuf b) {
            TransportPackets.CHUNK_REQUEST.encode(new MinecraftWireOutput(b), new TransportPackets.ChunkRequest(sessionId, requestId, startIndex, count));
        }
    }

    public record ChunkAcknowledgement(UUID sessionId, long requestId, int receivedThroughIndex, long bufferedMs) implements JammarrMessage {
        public static final ResourceLocation ID = id("chunk_ack");
        public static ChunkAcknowledgement read(FriendlyByteBuf b) {
            TransportPackets.ChunkAcknowledgement value = decode(TransportPackets.CHUNK_ACKNOWLEDGEMENT, b);
            return new ChunkAcknowledgement(value.sessionId(), value.requestId(), value.receivedThroughIndex(), value.bufferedMs());
        }
        public void write(FriendlyByteBuf b) {
            TransportPackets.CHUNK_ACKNOWLEDGEMENT.encode(new MinecraftWireOutput(b),
                    new TransportPackets.ChunkAcknowledgement(sessionId, requestId, receivedThroughIndex, bufferedMs));
        }
    }

    public record AudioHealth(UUID sessionId, String state, int recoveryAttempts, int underruns, int receivedChunks, long bufferedMs) implements JammarrMessage {
        public static final ResourceLocation ID = id("audio_health");
        public static AudioHealth read(FriendlyByteBuf b) {
            StatePackets.AudioHealth value = decode(StatePackets.AUDIO_HEALTH, b);
            return new AudioHealth(value.sessionId(), value.state(), value.recoveryAttempts(), value.underruns(),
                    value.receivedChunks(), value.bufferedMs());
        }
        public void write(FriendlyByteBuf b) {
            StatePackets.AUDIO_HEALTH.encode(new MinecraftWireOutput(b), new StatePackets.AudioHealth(
                    sessionId, state, recoveryAttempts, underruns, receivedChunks, bufferedMs));
        }
    }

    public record ManifestRequest(boolean forceRebuffer) implements JammarrMessage {
        public static final ResourceLocation ID = id("manifest_request");
        public static ManifestRequest read(FriendlyByteBuf b) {
            return new ManifestRequest(decode(StatePackets.MANIFEST_REQUEST, b).forceRebuffer());
        }
        public void write(FriendlyByteBuf b) {
            StatePackets.MANIFEST_REQUEST.encode(new MinecraftWireOutput(b), new StatePackets.ManifestRequest(forceRebuffer));
        }
    }

    public record AudioManifest(UUID sessionId, String title, String artist, int totalChunks, int firstChunk, long durationMs,
                                long startedAtEpochMs, boolean paused, long pausedPositionMs, String sha256) implements JammarrMessage {
        public static final ResourceLocation ID = id("audio_manifest");
        public static AudioManifest read(FriendlyByteBuf b) {
            TransportPackets.AudioManifest value = decode(TransportPackets.AUDIO_MANIFEST, b);
            return new AudioManifest(value.sessionId(), value.title(), value.artist(), value.totalChunks(), value.firstChunk(), value.durationMs(),
                    value.startedAtEpochMs(), value.paused(), value.pausedPositionMs(), value.sha256());
        }
        public void write(FriendlyByteBuf b) {
            TransportPackets.AUDIO_MANIFEST.encode(new MinecraftWireOutput(b), new TransportPackets.AudioManifest(sessionId, title, artist,
                    totalChunks, firstChunk, durationMs, startedAtEpochMs, paused, pausedPositionMs, sha256));
        }
    }

    public record AudioChunk(UUID sessionId, long requestId, int index, long startMs, String sha256, byte[] data) implements JammarrMessage {
        public static final ResourceLocation ID = id("audio_chunk");
        public static AudioChunk read(FriendlyByteBuf b) {
            TransportPackets.AudioChunk value = decode(TransportPackets.AUDIO_CHUNK, b);
            return new AudioChunk(value.sessionId(), value.requestId(), value.index(), value.startMs(), value.sha256(), value.data());
        }
        public void write(FriendlyByteBuf b) {
            TransportPackets.AUDIO_CHUNK.encode(new MinecraftWireOutput(b), new TransportPackets.AudioChunk(sessionId, requestId, index, startMs, sha256, data));
        }
    }

    public record PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                                long positionMs, long durationMs, long serverEpochMs, boolean operator,
                                PlaybackOrigin origin, String sourceName, List<QueueEntry> queue) implements JammarrMessage {
        public PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                             long positionMs, long durationMs, long serverEpochMs, boolean operator, List<QueueEntry> queue) {
            this(status, statusMessage, title, artist, paused, positionMs, durationMs, serverEpochMs, operator, PlaybackOrigin.NONE, "", queue);
        }
        public PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist, boolean paused,
                             long positionMs, long durationMs, long serverEpochMs, boolean operator,
                             PlaybackOrigin origin, List<QueueEntry> queue) {
            this(status, statusMessage, title, artist, paused, positionMs, durationMs, serverEpochMs, operator, origin, "", queue);
        }
        public static final ResourceLocation ID = id("playback_state");
        public static PlaybackState read(FriendlyByteBuf b) {
            StatePackets.PlaybackState value = decode(StatePackets.PLAYBACK_STATE, b);
            return new PlaybackState(enumValue(PlaybackStatus.class, value.status()), value.statusMessage(),
                    value.title(), value.artist(), value.paused(), value.positionMs(), value.durationMs(),
                    value.serverEpochMs(), value.operator(), enumValue(PlaybackOrigin.class, value.origin()),
                    value.sourceName(), value.queue().stream().map(JammarrPayloads::toPayload).toList());
        }
        public void write(FriendlyByteBuf b) {
            StatePackets.PLAYBACK_STATE.encode(new MinecraftWireOutput(b), new StatePackets.PlaybackState(
                    enumValue(StatePackets.PlaybackStatus.class, status), statusMessage, title, artist, paused,
                    positionMs, durationMs, serverEpochMs, operator, enumValue(StatePackets.PlaybackOrigin.class, origin),
                    sourceName, queue.stream().map(JammarrPayloads::toCore).toList()));
        }
    }

    public record StationState(StationType stationType, boolean active, boolean autoplayEnabled, long generation,
                               SonicCapability capability, String capabilityMessage, String name,
                               List<StationSeed> seeds, List<QueueEntry> preview) implements JammarrMessage {
        public static final ResourceLocation ID = id("station_state");
        public static StationState read(FriendlyByteBuf b) {
            StatePackets.StationState value = decode(StatePackets.STATION_STATE, b);
            return new StationState(enumValue(StationType.class, value.stationType()), value.active(),
                    value.autoplayEnabled(), value.generation(), enumValue(SonicCapability.class, value.capability()),
                    value.capabilityMessage(), value.name(), value.seeds().stream().map(JammarrPayloads::toPayload).toList(),
                    value.preview().stream().map(JammarrPayloads::toPayload).toList());
        }
        public void write(FriendlyByteBuf b) {
            StatePackets.STATION_STATE.encode(new MinecraftWireOutput(b), new StatePackets.StationState(
                    enumValue(StationModels.StationType.class, stationType), active, autoplayEnabled, generation,
                    enumValue(StationModels.SonicCapability.class, capability), capabilityMessage, name,
                    seeds.stream().map(JammarrPayloads::toCore).toList(),
                    preview.stream().map(JammarrPayloads::toCore).toList()));
        }
    }

    public record AdventurePreview(long generation, String message, List<QueueEntry> path) implements JammarrMessage {
        public static final ResourceLocation ID = id("adventure_preview");
        public static AdventurePreview read(FriendlyByteBuf b) {
            StatePackets.AdventurePreview value = decode(StatePackets.ADVENTURE_PREVIEW, b);
            return new AdventurePreview(value.generation(), value.message(),
                    value.path().stream().map(JammarrPayloads::toPayload).toList());
        }
        public void write(FriendlyByteBuf b) {
            StatePackets.ADVENTURE_PREVIEW.encode(new MinecraftWireOutput(b), new StatePackets.AdventurePreview(
                    generation, message, path.stream().map(JammarrPayloads::toCore).toList()));
        }
    }

    public record ErrorMessage(ErrorCode code, String message) implements JammarrMessage {
        public static final ResourceLocation ID = id("error");
        public static ErrorMessage read(FriendlyByteBuf b) {
            StatePackets.ErrorMessage value = decode(StatePackets.ERROR_MESSAGE, b);
            return new ErrorMessage(enumValue(ErrorCode.class, value.code()), value.message());
        }
        public void write(FriendlyByteBuf b) {
            StatePackets.ERROR_MESSAGE.encode(new MinecraftWireOutput(b), new StatePackets.ErrorMessage(
                    enumValue(StatePackets.ErrorCode.class, code), message));
        }
    }

    public static ResourceLocation idOf(JammarrMessage message) {
        if (message instanceof OpenScreen) return OpenScreen.ID;
        if (message instanceof ClientHello) return ClientHello.ID;
        if (message instanceof ServerHello) return ServerHello.ID;
        if (message instanceof TimeSyncRequest) return TimeSyncRequest.ID;
        if (message instanceof TimeSyncResponse) return TimeSyncResponse.ID;
        if (message instanceof BrowseRequest) return BrowseRequest.ID;
        if (message instanceof BrowseResults) return BrowseResults.ID;
        if (message instanceof QueueRequest) return QueueRequest.ID;
        if (message instanceof ControlRequest) return ControlRequest.ID;
        if (message instanceof StationRequest) return StationRequest.ID;
        if (message instanceof ChunkRequest) return ChunkRequest.ID;
        if (message instanceof ChunkAcknowledgement) return ChunkAcknowledgement.ID;
        if (message instanceof AudioHealth) return AudioHealth.ID;
        if (message instanceof ManifestRequest) return ManifestRequest.ID;
        if (message instanceof AudioManifest) return AudioManifest.ID;
        if (message instanceof AudioChunk) return AudioChunk.ID;
        if (message instanceof PlaybackState) return PlaybackState.ID;
        if (message instanceof StationState) return StationState.ID;
        if (message instanceof AdventurePreview) return AdventurePreview.ID;
        if (message instanceof ErrorMessage) return ErrorMessage.ID;
        throw new IllegalArgumentException("Unknown Jammarr message type: " + message.getClass().getName());
    }

    public static void write(JammarrMessage message, FriendlyByteBuf buffer) {
        if (message instanceof OpenScreen) return;
        if (message instanceof ClientHello value) value.write(buffer);
        else if (message instanceof ServerHello value) value.write(buffer);
        else if (message instanceof TimeSyncRequest value) value.write(buffer);
        else if (message instanceof TimeSyncResponse value) value.write(buffer);
        else if (message instanceof BrowseRequest value) value.write(buffer);
        else if (message instanceof BrowseResults value) value.write(buffer);
        else if (message instanceof QueueRequest value) value.write(buffer);
        else if (message instanceof ControlRequest value) value.write(buffer);
        else if (message instanceof StationRequest value) value.write(buffer);
        else if (message instanceof ChunkRequest value) value.write(buffer);
        else if (message instanceof ChunkAcknowledgement value) value.write(buffer);
        else if (message instanceof AudioHealth value) value.write(buffer);
        else if (message instanceof ManifestRequest value) value.write(buffer);
        else if (message instanceof AudioManifest value) value.write(buffer);
        else if (message instanceof AudioChunk value) value.write(buffer);
        else if (message instanceof PlaybackState value) value.write(buffer);
        else if (message instanceof StationState value) value.write(buffer);
        else if (message instanceof AdventurePreview value) value.write(buffer);
        else if (message instanceof ErrorMessage value) value.write(buffer);
        else throw new IllegalArgumentException("Unknown Jammarr message type: " + message.getClass().getName());
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

    private static <T> T decode(WireCodec<T> codec, FriendlyByteBuf buffer) {
        try {
            return codec.decode(new MinecraftWireInput(buffer));
        } catch (ProtocolException malformed) {
            throw new DecoderException(malformed.getMessage(), malformed);
        }
    }

    private JammarrPayloads() {}
}
