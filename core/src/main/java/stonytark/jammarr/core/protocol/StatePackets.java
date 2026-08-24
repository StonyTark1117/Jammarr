package stonytark.jammarr.core.protocol;

import stonytark.jammarr.core.model.StationModels.SonicCapability;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.model.StationModels.StationType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Shared protocol-5 playback, station-state, health, and error packet codecs. */
public final class StatePackets {
    public enum PlaybackStatus { IDLE, PREPARING, PLAYING, PAUSED, PLEX_OFFLINE }
    public enum ErrorCode { INVALID_REQUEST, PERMISSION_DENIED, RATE_LIMITED, QUEUE_FULL, PLEX_OFFLINE, TRACK_FAILED, INTERNAL }
    public enum PlaybackOrigin { NONE, MANUAL, STATION, ADVENTURE }

    public static final WireCodec<QueueEntry> QUEUE_ENTRY = new WireCodec<QueueEntry>() {
        @Override public QueueEntry decode(WireInput input) {
            return new QueueEntry(input.readUtf(256), input.readUtf(256), input.readUtf(256), input.readVarLong(),
                    readEnum(input, PlaybackOrigin.class), input.readBoolean());
        }
        @Override public void encode(WireOutput output, QueueEntry value) {
            output.writeUtf(value.key(), 256);
            output.writeUtf(value.title(), 256);
            output.writeUtf(value.artist(), 256);
            output.writeVarLong(value.durationMs());
            writeEnum(output, value.source());
            output.writeBoolean(value.editable());
        }
    };

    public static final WireCodec<AudioHealth> AUDIO_HEALTH = new WireCodec<AudioHealth>() {
        @Override public AudioHealth decode(WireInput input) {
            return new AudioHealth(input.readUuid(), input.readUtf(32), input.readVarInt(), input.readVarInt(),
                    input.readVarInt(), input.readVarLong());
        }
        @Override public void encode(WireOutput output, AudioHealth value) {
            output.writeUuid(value.sessionId());
            output.writeUtf(value.state(), 32);
            output.writeVarInt(value.recoveryAttempts());
            output.writeVarInt(value.underruns());
            output.writeVarInt(value.receivedChunks());
            output.writeVarLong(value.bufferedMs());
        }
    };

    public static final WireCodec<ManifestRequest> MANIFEST_REQUEST = new WireCodec<ManifestRequest>() {
        @Override public ManifestRequest decode(WireInput input) { return new ManifestRequest(input.readBoolean()); }
        @Override public void encode(WireOutput output, ManifestRequest value) { output.writeBoolean(value.forceRebuffer()); }
    };

    public static final WireCodec<PlaybackState> PLAYBACK_STATE = new WireCodec<PlaybackState>() {
        @Override public PlaybackState decode(WireInput input) {
            PlaybackStatus status = readEnum(input, PlaybackStatus.class);
            String statusMessage = input.readUtf(256);
            String title = input.readUtf(256);
            String artist = input.readUtf(256);
            boolean paused = input.readBoolean();
            long positionMs = input.readVarLong();
            long durationMs = input.readVarLong();
            long serverEpochMs = input.readLong();
            boolean operator = input.readBoolean();
            PlaybackOrigin origin = readEnum(input, PlaybackOrigin.class);
            String sourceName = input.readUtf(256);
            int count = boundedCount(input, ProtocolLimits.MAX_PLAYBACK_ENTRIES, "playback entries");
            List<QueueEntry> queue = new ArrayList<QueueEntry>(count);
            for (int i = 0; i < count; i++) queue.add(QUEUE_ENTRY.decode(input));
            return new PlaybackState(status, statusMessage, title, artist, paused, positionMs, durationMs,
                    serverEpochMs, operator, origin, sourceName, queue);
        }
        @Override public void encode(WireOutput output, PlaybackState value) {
            writeEnum(output, value.status());
            output.writeUtf(value.statusMessage(), 256);
            output.writeUtf(value.title(), 256);
            output.writeUtf(value.artist(), 256);
            output.writeBoolean(value.paused());
            output.writeVarLong(value.positionMs());
            output.writeVarLong(value.durationMs());
            output.writeLong(value.serverEpochMs());
            output.writeBoolean(value.operator());
            writeEnum(output, value.origin());
            output.writeUtf(value.sourceName(), 256);
            int count = Math.min(ProtocolLimits.MAX_PLAYBACK_ENTRIES, value.queue().size());
            output.writeVarInt(count);
            for (int i = 0; i < count; i++) QUEUE_ENTRY.encode(output, value.queue().get(i));
        }
    };

    public static final WireCodec<StationState> STATION_STATE = new WireCodec<StationState>() {
        @Override public StationState decode(WireInput input) {
            StationType type = readEnum(input, StationType.class);
            boolean active = input.readBoolean();
            boolean autoplay = input.readBoolean();
            long generation = input.readVarLong();
            SonicCapability capability = readEnum(input, SonicCapability.class);
            String message = input.readUtf(256);
            String name = input.readUtf(256);
            int seedCount = boundedCount(input, ProtocolLimits.MAX_STATION_SEEDS, "station seeds");
            List<StationSeed> seeds = new ArrayList<StationSeed>(seedCount);
            for (int i = 0; i < seedCount; i++) seeds.add(ControlPackets.STATION_SEED.decode(input));
            int previewCount = boundedCount(input, ProtocolLimits.MAX_STATION_PREVIEW, "station preview");
            List<QueueEntry> preview = new ArrayList<QueueEntry>(previewCount);
            for (int i = 0; i < previewCount; i++) preview.add(QUEUE_ENTRY.decode(input));
            return new StationState(type, active, autoplay, generation, capability, message, name, seeds, preview);
        }
        @Override public void encode(WireOutput output, StationState value) {
            writeEnum(output, value.stationType());
            output.writeBoolean(value.active());
            output.writeBoolean(value.autoplayEnabled());
            output.writeVarLong(value.generation());
            writeEnum(output, value.capability());
            output.writeUtf(value.capabilityMessage(), 256);
            output.writeUtf(value.name(), 256);
            int seedCount = Math.min(ProtocolLimits.MAX_STATION_SEEDS, value.seeds().size());
            output.writeVarInt(seedCount);
            for (int i = 0; i < seedCount; i++) ControlPackets.STATION_SEED.encode(output, value.seeds().get(i));
            int previewCount = Math.min(ProtocolLimits.MAX_STATION_PREVIEW, value.preview().size());
            output.writeVarInt(previewCount);
            for (int i = 0; i < previewCount; i++) QUEUE_ENTRY.encode(output, value.preview().get(i));
        }
    };

    public static final WireCodec<AdventurePreview> ADVENTURE_PREVIEW = new WireCodec<AdventurePreview>() {
        @Override public AdventurePreview decode(WireInput input) {
            long generation = input.readVarLong();
            String message = input.readUtf(256);
            int count = boundedCount(input, ProtocolLimits.MAX_ADVENTURE_PATH, "Adventure path");
            List<QueueEntry> path = new ArrayList<QueueEntry>(count);
            for (int i = 0; i < count; i++) path.add(QUEUE_ENTRY.decode(input));
            return new AdventurePreview(generation, message, path);
        }
        @Override public void encode(WireOutput output, AdventurePreview value) {
            output.writeVarLong(value.generation());
            output.writeUtf(value.message(), 256);
            int count = Math.min(ProtocolLimits.MAX_ADVENTURE_PATH, value.path().size());
            output.writeVarInt(count);
            for (int i = 0; i < count; i++) QUEUE_ENTRY.encode(output, value.path().get(i));
        }
    };

    public static final WireCodec<ErrorMessage> ERROR_MESSAGE = new WireCodec<ErrorMessage>() {
        @Override public ErrorMessage decode(WireInput input) {
            return new ErrorMessage(readEnum(input, ErrorCode.class), input.readUtf(512));
        }
        @Override public void encode(WireOutput output, ErrorMessage value) {
            writeEnum(output, value.code());
            output.writeUtf(value.message(), 512);
        }
    };

    public static final class QueueEntry implements JammarrMessage {
        private final String key;
        private final String title;
        private final String artist;
        private final long durationMs;
        private final PlaybackOrigin source;
        private final boolean editable;
        public QueueEntry(String key, String title, String artist, long durationMs,
                          PlaybackOrigin source, boolean editable) {
            this.key = safe(key);
            this.title = safe(title);
            this.artist = safe(artist);
            this.durationMs = durationMs;
            this.source = require(source, "source");
            this.editable = editable;
        }
        public String key() { return key; }
        public String title() { return title; }
        public String artist() { return artist; }
        public long durationMs() { return durationMs; }
        public PlaybackOrigin source() { return source; }
        public boolean editable() { return editable; }
    }

    public static final class AudioHealth implements JammarrMessage {
        private final UUID sessionId;
        private final String state;
        private final int recoveryAttempts;
        private final int underruns;
        private final int receivedChunks;
        private final long bufferedMs;
        public AudioHealth(UUID sessionId, String state, int recoveryAttempts, int underruns,
                           int receivedChunks, long bufferedMs) {
            this.sessionId = require(sessionId, "sessionId");
            this.state = safe(state);
            this.recoveryAttempts = recoveryAttempts;
            this.underruns = underruns;
            this.receivedChunks = receivedChunks;
            this.bufferedMs = bufferedMs;
        }
        public UUID sessionId() { return sessionId; }
        public String state() { return state; }
        public int recoveryAttempts() { return recoveryAttempts; }
        public int underruns() { return underruns; }
        public int receivedChunks() { return receivedChunks; }
        public long bufferedMs() { return bufferedMs; }
    }

    public static final class ManifestRequest implements JammarrMessage {
        private final boolean forceRebuffer;
        public ManifestRequest(boolean forceRebuffer) { this.forceRebuffer = forceRebuffer; }
        public boolean forceRebuffer() { return forceRebuffer; }
    }

    public static final class PlaybackState implements JammarrMessage {
        private final PlaybackStatus status;
        private final String statusMessage;
        private final String title;
        private final String artist;
        private final boolean paused;
        private final long positionMs;
        private final long durationMs;
        private final long serverEpochMs;
        private final boolean operator;
        private final PlaybackOrigin origin;
        private final String sourceName;
        private final List<QueueEntry> queue;
        public PlaybackState(PlaybackStatus status, String statusMessage, String title, String artist,
                             boolean paused, long positionMs, long durationMs, long serverEpochMs,
                             boolean operator, PlaybackOrigin origin, String sourceName, List<QueueEntry> queue) {
            this.status = require(status, "status");
            this.statusMessage = safe(statusMessage);
            this.title = safe(title);
            this.artist = safe(artist);
            this.paused = paused;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.serverEpochMs = serverEpochMs;
            this.operator = operator;
            this.origin = require(origin, "origin");
            this.sourceName = safe(sourceName);
            this.queue = immutable(queue);
        }
        public PlaybackStatus status() { return status; }
        public String statusMessage() { return statusMessage; }
        public String title() { return title; }
        public String artist() { return artist; }
        public boolean paused() { return paused; }
        public long positionMs() { return positionMs; }
        public long durationMs() { return durationMs; }
        public long serverEpochMs() { return serverEpochMs; }
        public boolean operator() { return operator; }
        public PlaybackOrigin origin() { return origin; }
        public String sourceName() { return sourceName; }
        public List<QueueEntry> queue() { return queue; }
    }

    public static final class StationState implements JammarrMessage {
        private final StationType stationType;
        private final boolean active;
        private final boolean autoplayEnabled;
        private final long generation;
        private final SonicCapability capability;
        private final String capabilityMessage;
        private final String name;
        private final List<StationSeed> seeds;
        private final List<QueueEntry> preview;
        public StationState(StationType stationType, boolean active, boolean autoplayEnabled, long generation,
                            SonicCapability capability, String capabilityMessage, String name,
                            List<StationSeed> seeds, List<QueueEntry> preview) {
            this.stationType = require(stationType, "stationType");
            this.active = active;
            this.autoplayEnabled = autoplayEnabled;
            this.generation = generation;
            this.capability = require(capability, "capability");
            this.capabilityMessage = safe(capabilityMessage);
            this.name = safe(name);
            this.seeds = immutable(seeds);
            this.preview = immutable(preview);
        }
        public StationType stationType() { return stationType; }
        public boolean active() { return active; }
        public boolean autoplayEnabled() { return autoplayEnabled; }
        public long generation() { return generation; }
        public SonicCapability capability() { return capability; }
        public String capabilityMessage() { return capabilityMessage; }
        public String name() { return name; }
        public List<StationSeed> seeds() { return seeds; }
        public List<QueueEntry> preview() { return preview; }
    }

    public static final class AdventurePreview implements JammarrMessage {
        private final long generation;
        private final String message;
        private final List<QueueEntry> path;
        public AdventurePreview(long generation, String message, List<QueueEntry> path) {
            this.generation = generation;
            this.message = safe(message);
            this.path = immutable(path);
        }
        public long generation() { return generation; }
        public String message() { return message; }
        public List<QueueEntry> path() { return path; }
    }

    public static final class ErrorMessage implements JammarrMessage {
        private final ErrorCode code;
        private final String message;
        public ErrorMessage(ErrorCode code, String message) {
            this.code = require(code, "code");
            this.message = safe(message);
        }
        public ErrorCode code() { return code; }
        public String message() { return message; }
    }

    private static int boundedCount(WireInput input, int maximum, String field) {
        int count = input.readVarInt();
        if (count < 0 || count > maximum) throw new ProtocolException("Jammarr " + field + " count exceeds " + maximum);
        return count;
    }
    private static <T extends Enum<T>> T readEnum(WireInput input, Class<T> type) {
        int ordinal = input.readVarInt();
        T[] values = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= values.length) throw new ProtocolException(
                "Invalid " + type.getSimpleName() + " ordinal " + ordinal);
        return values[ordinal];
    }
    private static void writeEnum(WireOutput output, Enum<?> value) {
        if (value == null) throw new ProtocolException("Enum value is null");
        output.writeVarInt(value.ordinal());
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name);
        return value;
    }
    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values == null ? Collections.<T>emptyList() : values));
    }
    private StatePackets() {}
}
