package stonytark.pampmod.client;

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
        if (inFlight != null && nowMs - inFlight.sentAtMs < retryAfterMs) return Optional.empty();
        int count = Math.min(windowSize, totalChunks - firstMissing);
        inFlight = new Request(nextRequestId++, firstMissing, count, nowMs);
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
        return Optional.of(acknowledgement);
    }

    public synchronized void reject(long requestId) { if (inFlight != null && inFlight.id == requestId) inFlight = null; }
    public synchronized int firstMissing() { return firstMissing; }
    public synchronized boolean complete() { return firstMissing >= totalChunks; }

    public record Request(long id, int startIndex, int count, long sentAtMs) {}
    public record Acknowledgement(long requestId, int receivedThroughIndex) {}
}
