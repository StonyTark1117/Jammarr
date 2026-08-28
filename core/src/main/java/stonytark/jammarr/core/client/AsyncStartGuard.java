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
        return complete(token, () -> {});
    }

    /** Publishes a completed start before another caller can begin one. */
    public synchronized boolean complete(long token, Runnable publish) {
        if (!pending || token != generation) return false;
        try {
            publish.run();
        } finally {
            pending = false;
        }
        return true;
    }

    public synchronized void cancel() {
        generation++;
        pending = false;
    }

    public synchronized boolean pending() { return pending; }
}
