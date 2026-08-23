package stonytark.jammarr.core.server;

/** Small monotonic-time gate used to keep failed asynchronous work off the server tick loop. */
public final class RetryGate {
    private long retryAtMs;

    public boolean ready(long nowMs) { return nowMs >= retryAtMs; }
    public void deferUntil(long retryAtMs) { this.retryAtMs = Math.max(this.retryAtMs, retryAtMs); }
    public void clear() { retryAtMs = 0; }
    public long retryAtMs() { return retryAtMs; }
}
