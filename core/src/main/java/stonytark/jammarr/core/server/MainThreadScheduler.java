package stonytark.jammarr.core.server;

/** Schedules asynchronous Plex/cache completions on the Minecraft server thread. */
public interface MainThreadScheduler {
    void execute(Runnable action);
}
