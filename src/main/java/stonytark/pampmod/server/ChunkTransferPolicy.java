package stonytark.pampmod.server;

import java.util.Optional;
import java.util.UUID;

/** Server-authoritative flow control for one client's compressed-audio window. */
public final class ChunkTransferPolicy {
    public static final int MAX_CHUNKS_PER_REQUEST = 8;
    public static final long RETRY_AFTER_MS = 1_500;
    public static final long MAX_BUFFERED_MS = 12_000;
    private static final long MAX_BUFFER_REPORT_MS = 60_000;

    public static boolean acceptsRequest(State previous, UUID sessionId, long requestId, int startIndex, int count,
                                         int totalChunks, long nowMs) {
        if (sessionId == null || requestId < 1 || startIndex < 0 || count < 1 || count > MAX_CHUNKS_PER_REQUEST) return false;
        long endExclusive = (long) startIndex + count;
        if (endExclusive > totalChunks) return false;
        if (previous == null || !previous.sessionId().equals(sessionId)) return true;
        if (requestId <= previous.requestId()) return false;

        if (!previous.acknowledged()) {
            return nowMs - previous.lastSeenMs() >= RETRY_AFTER_MS
                    && startIndex == previous.startIndex()
                    && endExclusive - 1 == previous.endIndex();
        }

        long elapsed = Math.max(0, nowMs - previous.lastSeenMs());
        long estimatedBuffer = Math.max(0, previous.bufferedMs() - elapsed);
        return estimatedBuffer < MAX_BUFFERED_MS && startIndex == previous.endIndex() + 1;
    }

    public static State begin(UUID sessionId, long requestId, int startIndex, int count, long nowMs) {
        return new State(sessionId, requestId, startIndex, startIndex + count - 1, -1, 0, nowMs);
    }

    public static State initial(UUID sessionId, int firstIndex, long nowMs) {
        return new State(sessionId, 0, firstIndex, firstIndex - 1, firstIndex - 1, 0, nowMs);
    }

    public static Optional<State> acknowledge(State state, UUID sessionId, long requestId, int receivedThroughIndex,
                                              long bufferedMs, long nowMs) {
        if (state == null || !state.sessionId().equals(sessionId) || state.requestId() != requestId) return Optional.empty();
        if (receivedThroughIndex != state.endIndex() || bufferedMs < 0 || bufferedMs > MAX_BUFFER_REPORT_MS) return Optional.empty();
        return Optional.of(new State(state.sessionId(), state.requestId(), state.startIndex(), state.endIndex(),
                receivedThroughIndex, bufferedMs, nowMs));
    }

    public static boolean withinPlaybackLead(long chunkStartMs, long playbackPositionMs, long schedulingLeadMs) {
        if (chunkStartMs < 0 || playbackPositionMs < 0 || schedulingLeadMs < 0) return false;
        long maximum = playbackPositionMs > Long.MAX_VALUE - schedulingLeadMs
                ? Long.MAX_VALUE : playbackPositionMs + schedulingLeadMs;
        return chunkStartMs <= maximum;
    }

    public record State(UUID sessionId, long requestId, int startIndex, int endIndex, int acknowledgedThrough,
                        long bufferedMs, long lastSeenMs) {
        public boolean acknowledged() { return acknowledgedThrough == endIndex; }
    }

    private ChunkTransferPolicy() {}
}
