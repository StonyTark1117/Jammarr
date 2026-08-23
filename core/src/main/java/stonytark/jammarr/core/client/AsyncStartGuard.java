package stonytark.jammarr.core.client;

/** Prevents duplicate asynchronous audio-channel creation and rejects stale completions after a reset. */
public final class AsyncStartGuard {
    private long generation;
    private boolean pending;

    public synchronized long begin() {
        if (pending) return -1;
        pending = true;
        return generation;
    }

    public synchronized boolean complete(long token) {
        if (!pending || token != generation) return false;
        pending = false;
        return true;
    }

    public synchronized void cancel() {
        generation++;
        pending = false;
    }

    public synchronized boolean pending() { return pending; }
}
