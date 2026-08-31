package stonytark.jammarr.core.client;

import stonytark.jammarr.core.protocol.ControlPackets;

/**
 * Tracks one client browse request and guarantees a bounded terminal state.
 * Screens own presentation; this class owns matching, cancellation, and the
 * timeout boundary so late responses cannot leave controls permanently busy.
 */
public final class PendingBrowseRequest {
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;

    private boolean active;
    private ControlPackets.BrowseKind kind;
    private String query = "";
    private int page;
    private long startedAtMs;

    public synchronized void begin(ControlPackets.BrowseKind kind, String query, int page, long nowMs) {
        if (kind == null) throw new IllegalArgumentException("Browse kind is required");
        this.kind = kind;
        this.query = query == null ? "" : query;
        this.page = Math.max(0, page);
        this.startedAtMs = nowMs;
        this.active = true;
    }

    public synchronized boolean complete(ControlPackets.BrowseResults result) {
        if (!active || result == null || result.kind() != kind || result.page() != page
                || !query.equals(result.query())) return false;
        active = false;
        return true;
    }

    public synchronized boolean fail() {
        if (!active) return false;
        active = false;
        return true;
    }

    public synchronized boolean cancel() {
        if (!active) return false;
        active = false;
        return true;
    }

    public synchronized boolean expire(long nowMs) {
        return expire(nowMs, DEFAULT_TIMEOUT_MS);
    }

    public synchronized boolean expire(long nowMs, long timeoutMs) {
        if (!active || timeoutMs < 1L || nowMs - startedAtMs < timeoutMs) return false;
        active = false;
        return true;
    }

    public synchronized boolean active() { return active; }
}
