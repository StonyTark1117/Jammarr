package stonytark.jammarr.core.protocol;

import java.util.Arrays;
import java.util.UUID;

public final class TransportPackets {
    public static final WireCodec<ChunkRequest> CHUNK_REQUEST = new WireCodec<ChunkRequest>() {
        @Override public ChunkRequest decode(WireInput input) {
            return new ChunkRequest(input.readUuid(), input.readVarLong(), input.readVarInt(), input.readVarInt());
        }
        @Override public void encode(WireOutput output, ChunkRequest value) {
            output.writeUuid(value.sessionId()); output.writeVarLong(value.requestId());
            output.writeVarInt(value.startIndex()); output.writeVarInt(value.count());
        }
    };

    public static final WireCodec<ChunkAcknowledgement> CHUNK_ACKNOWLEDGEMENT = new WireCodec<ChunkAcknowledgement>() {
        @Override public ChunkAcknowledgement decode(WireInput input) {
            return new ChunkAcknowledgement(input.readUuid(), input.readVarLong(), input.readVarInt(), input.readVarLong());
        }
        @Override public void encode(WireOutput output, ChunkAcknowledgement value) {
            output.writeUuid(value.sessionId()); output.writeVarLong(value.requestId());
            output.writeVarInt(value.receivedThroughIndex()); output.writeVarLong(value.bufferedMs());
        }
    };

    public static final WireCodec<AudioManifest> AUDIO_MANIFEST = new WireCodec<AudioManifest>() {
        @Override public AudioManifest decode(WireInput input) {
            return new AudioManifest(input.readUuid(), input.readUtf(256), input.readUtf(256), input.readVarInt(),
                    input.readVarInt(), input.readVarLong(), input.readLong(), input.readBoolean(), input.readVarLong(), input.readUtf(64));
        }
        @Override public void encode(WireOutput output, AudioManifest value) {
            output.writeUuid(value.sessionId()); output.writeUtf(value.title(), 256); output.writeUtf(value.artist(), 256);
            output.writeVarInt(value.totalChunks()); output.writeVarInt(value.firstChunk()); output.writeVarLong(value.durationMs());
            output.writeLong(value.startedAtEpochMs()); output.writeBoolean(value.paused());
            output.writeVarLong(value.pausedPositionMs()); output.writeUtf(value.sha256(), 64);
        }
    };

    public static final WireCodec<AudioChunk> AUDIO_CHUNK = new WireCodec<AudioChunk>() {
        @Override public AudioChunk decode(WireInput input) {
            return new AudioChunk(input.readUuid(), input.readVarLong(), input.readVarInt(), input.readVarLong(),
                    input.readUtf(64), input.readByteArray(ProtocolLimits.MAX_AUDIO_CHUNK_BYTES));
        }
        @Override public void encode(WireOutput output, AudioChunk value) {
            output.writeUuid(value.sessionId()); output.writeVarLong(value.requestId()); output.writeVarInt(value.index());
            output.writeVarLong(value.startMs()); output.writeUtf(value.sha256(), 64);
            output.writeByteArray(value.data(), ProtocolLimits.MAX_AUDIO_CHUNK_BYTES);
        }
    };

    public static final class ChunkRequest implements JammarrMessage {
        private final UUID sessionId; private final long requestId; private final int startIndex; private final int count;
        public ChunkRequest(UUID sessionId, long requestId, int startIndex, int count) {
            this.sessionId = sessionId; this.requestId = requestId; this.startIndex = startIndex; this.count = count;
        }
        public UUID sessionId() { return sessionId; }
        public long requestId() { return requestId; }
        public int startIndex() { return startIndex; }
        public int count() { return count; }
    }

    public static final class ChunkAcknowledgement implements JammarrMessage {
        private final UUID sessionId; private final long requestId; private final int receivedThroughIndex; private final long bufferedMs;
        public ChunkAcknowledgement(UUID sessionId, long requestId, int receivedThroughIndex, long bufferedMs) {
            this.sessionId = sessionId; this.requestId = requestId; this.receivedThroughIndex = receivedThroughIndex; this.bufferedMs = bufferedMs;
        }
        public UUID sessionId() { return sessionId; }
        public long requestId() { return requestId; }
        public int receivedThroughIndex() { return receivedThroughIndex; }
        public long bufferedMs() { return bufferedMs; }
    }

    public static final class AudioManifest implements JammarrMessage {
        private final UUID sessionId; private final String title; private final String artist;
        private final int totalChunks; private final int firstChunk; private final long durationMs; private final long startedAtEpochMs;
        private final boolean paused; private final long pausedPositionMs; private final String sha256;
        public AudioManifest(UUID sessionId, String title, String artist, int totalChunks, int firstChunk, long durationMs,
                             long startedAtEpochMs, boolean paused, long pausedPositionMs, String sha256) {
            this.sessionId = sessionId; this.title = title; this.artist = artist; this.totalChunks = totalChunks;
            this.firstChunk = firstChunk; this.durationMs = durationMs; this.startedAtEpochMs = startedAtEpochMs;
            this.paused = paused; this.pausedPositionMs = pausedPositionMs; this.sha256 = sha256;
        }
        public UUID sessionId() { return sessionId; }
        public String title() { return title; }
        public String artist() { return artist; }
        public int totalChunks() { return totalChunks; }
        public int firstChunk() { return firstChunk; }
        public long durationMs() { return durationMs; }
        public long startedAtEpochMs() { return startedAtEpochMs; }
        public boolean paused() { return paused; }
        public long pausedPositionMs() { return pausedPositionMs; }
        public String sha256() { return sha256; }
    }

    public static final class AudioChunk implements JammarrMessage {
        private final UUID sessionId; private final long requestId; private final int index; private final long startMs;
        private final String sha256; private final byte[] data;
        public AudioChunk(UUID sessionId, long requestId, int index, long startMs, String sha256, byte[] data) {
            this.sessionId = sessionId; this.requestId = requestId; this.index = index; this.startMs = startMs;
            this.sha256 = sha256; this.data = data == null ? null : Arrays.copyOf(data, data.length);
        }
        public UUID sessionId() { return sessionId; }
        public long requestId() { return requestId; }
        public int index() { return index; }
        public long startMs() { return startMs; }
        public String sha256() { return sha256; }
        public byte[] data() { return data == null ? null : Arrays.copyOf(data, data.length); }
    }

    private TransportPackets() {}
}
