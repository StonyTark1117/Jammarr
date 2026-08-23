package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.RestartMode;

public final class RestartPolicy {
    public static Restoration restore(RestartMode mode, long checkpointMs, boolean paused) {
        switch (mode) {
            case CLEAR: return new Restoration(true, 0, false);
            case RESTART_TRACK: return new Restoration(false, 0, false);
            case RESUME_POSITION: return new Restoration(false, Math.max(0, checkpointMs), paused);
            default: throw new IllegalArgumentException("Unknown restart mode " + mode);
        }
    }

    public static final class Restoration {
        private final boolean clearQueue;
        private final long positionMs;
        private final boolean paused;
        public Restoration(boolean clearQueue, long positionMs, boolean paused) {
            this.clearQueue = clearQueue; this.positionMs = positionMs; this.paused = paused;
        }
        public boolean clearQueue() { return clearQueue; }
        public long positionMs() { return positionMs; }
        public boolean paused() { return paused; }
    }

    private RestartPolicy() {}
}
