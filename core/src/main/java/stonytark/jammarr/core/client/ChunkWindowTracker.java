package stonytark.jammarr.core.client;

import java.util.BitSet;
import java.util.Optional;

/** Tracks one pull window at a time so dropped requests or chunks are deterministically retried. */
public final class ChunkWindowTracker {
    private final int totalChunks;
    private final int windowSize;
    private final long retryAfterMs;
    private final BitSet received;
    private int firstMissing;
    private long nextRequestId = 1;
    private Request inFlight;
    private boolean retryImmediately;

    public ChunkWindowTracker(int firstChunk, int totalChunks, int windowSize, long retryAfterMs) {
        if (firstChunk < 0 || totalChunks < firstChunk || windowSize < 1 || retryAfterMs < 1) throw new IllegalArgumentException("Invalid chunk window");
        this.firstMissing = firstChunk;
        this.totalChunks = totalChunks;
        this.windowSize = windowSize;
        this.retryAfterMs = retryAfterMs;
        this.received = new BitSet(totalChunks);
    }

    public synchronized Optional<Request> request(long nowMs, long bufferedMs, long maximumBufferedMs) {
        if (firstMissing >= totalChunks || bufferedMs >= maximumBufferedMs) return Optional.empty();
        int startIndex;
        int count;
        if (inFlight != null) {
            if (!retryImmediately && nowMs - inFlight.sentAtMs < retryAfterMs) {
                return Optional.empty();
            }
            // The server admits a retry only when it has the same atomic
            // bounds as the unacknowledged request. firstMissing may already
            // have advanced after a partial delivery, so rebuilding a window
            // from it would deadlock client and server on different bounds.
            startIndex = inFlight.startIndex;
            count = inFlight.count;
        } else {
            startIndex = firstMissing;
            count = Math.min(windowSize, totalChunks - firstMissing);
        }
        inFlight = new Request(nextRequestId++, startIndex, count, nowMs);
        retryImmediately = false;
        return Optional.of(inFlight);
    }

    public synchronized Optional<Acknowledgement> received(long requestId, int index) {
        if (index < firstMissing || index >= totalChunks) return Optional.empty();
        received.set(index);
        while (firstMissing < totalChunks && received.get(firstMissing)) firstMissing++;
        if (inFlight == null || inFlight.id != requestId) return Optional.empty();
        int end = inFlight.startIndex + inFlight.count;
        for (int i = inFlight.startIndex; i < end; i++) if (!received.get(i)) return Optional.empty();
        Acknowledgement acknowledgement = new Acknowledgement(inFlight.id, end - 1);
        inFlight = null;
        retryImmediately = false;
        return Optional.of(acknowledgement);
    }

    public synchronized void reject(long requestId) {
        if (inFlight != null && inFlight.id == requestId) retryImmediately = true;
    }
    public synchronized int firstMissing() { return firstMissing; }
    public synchronized boolean complete() { return firstMissing >= totalChunks; }

    public static final class Request {
        private final long id;
        private final int startIndex;
        private final int count;
        private final long sentAtMs;

        public Request(long id, int startIndex, int count, long sentAtMs) {
            this.id = id;
            this.startIndex = startIndex;
            this.count = count;
            this.sentAtMs = sentAtMs;
        }

        public long id() { return id; }
        public int startIndex() { return startIndex; }
        public int count() { return count; }
        public long sentAtMs() { return sentAtMs; }
    }

    public static final class Acknowledgement {
        private final long requestId;
        private final int receivedThroughIndex;

        public Acknowledgement(long requestId, int receivedThroughIndex) {
            this.requestId = requestId;
            this.receivedThroughIndex = receivedThroughIndex;
        }

        public long requestId() { return requestId; }
        public int receivedThroughIndex() { return receivedThroughIndex; }
    }
}
