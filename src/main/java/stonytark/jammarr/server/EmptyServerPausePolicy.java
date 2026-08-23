package stonytark.jammarr.server;

public final class EmptyServerPausePolicy {
    public static boolean shouldPause(boolean configured, boolean empty, boolean active, boolean paused) { return configured && empty && active && !paused; }
    public static boolean shouldResume(boolean autoPaused, boolean empty) { return autoPaused && !empty; }
    private EmptyServerPausePolicy() {}
}
