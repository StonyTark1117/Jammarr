package stonytark.jammarr.core.protocol;

import stonytark.jammarr.core.server.ChunkTransferPolicy;

/** Protocol-6 feature bits and bounded transport values exchanged during hello. */
public final class ProtocolCapabilities {
    public static final long AUDIO_STREAMING = 1L;
    public static final long STATIONS = 1L << 1;
    public static final long SONIC_ADVENTURE = 1L << 2;
    public static final long OPTIONAL_CLIENT = 1L << 3;
    public static final long SUPPORTED_FEATURES = AUDIO_STREAMING | STATIONS | SONIC_ADVENTURE | OPTIONAL_CLIENT;

    public static final int AUDIO_CHUNK_BYTES = ProtocolLimits.MAX_AUDIO_CHUNK_BYTES;
    public static final int CHUNKS_PER_REQUEST = ChunkTransferPolicy.MAX_CHUNKS_PER_REQUEST;

    public static Negotiated negotiate(long offeredFeatures, int offeredChunkBytes, int offeredChunksPerRequest) {
        long features = offeredFeatures & SUPPORTED_FEATURES;
        if (offeredChunkBytes < AUDIO_CHUNK_BYTES) features &= ~AUDIO_STREAMING;
        int chunkBytes = boundedMinimum(offeredChunkBytes, AUDIO_CHUNK_BYTES, "audio chunk bytes");
        int chunks = boundedMinimum(offeredChunksPerRequest, CHUNKS_PER_REQUEST, "chunks per request");
        return new Negotiated(features, chunkBytes, chunks);
    }

    private static int boundedMinimum(int offered, int supported, String name) {
        if (offered < 1) throw new ProtocolException(name + " must be positive");
        return Math.min(offered, supported);
    }

    public static final class Negotiated {
        private final long features;
        private final int audioChunkBytes;
        private final int chunksPerRequest;

        private Negotiated(long features, int audioChunkBytes, int chunksPerRequest) {
            this.features = features;
            this.audioChunkBytes = audioChunkBytes;
            this.chunksPerRequest = chunksPerRequest;
        }

        public long features() { return features; }
        public int audioChunkBytes() { return audioChunkBytes; }
        public int chunksPerRequest() { return chunksPerRequest; }
        public boolean supports(long feature) { return (features & feature) == feature; }
    }

    private ProtocolCapabilities() {}
}
