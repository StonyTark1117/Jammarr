package stonytark.jammarr.server;

import stonytark.jammarr.config.JammarrConfig;

public final class RestartPolicy {
    public static Restoration restore(JammarrConfig.RestartMode mode, long checkpointMs, boolean paused) {
        return switch (mode) {
            case CLEAR -> new Restoration(true, 0, false);
            case RESTART_TRACK -> new Restoration(false, 0, false);
            case RESUME_POSITION -> new Restoration(false, Math.max(0, checkpointMs), paused);
        };
    }

    public record Restoration(boolean clearQueue, long positionMs, boolean paused) {}
    private RestartPolicy() {}
}
