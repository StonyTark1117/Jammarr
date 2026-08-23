package stonytark.jammarr.server;

import java.util.function.LongSupplier;

/** Authoritative playback clock isolated from Minecraft for deterministic tests. */
public final class PlaybackTimeline {
    private final LongSupplier clock;
    private long durationMs;
    private long startedAtMs;
    private long pausedPositionMs;
    private boolean paused;
    private boolean active;

    public PlaybackTimeline(LongSupplier clock) { this.clock = clock; }

    public void schedule(long durationMs, long positionMs, boolean paused, long delayMs) {
        this.durationMs = Math.max(0, durationMs);
        this.pausedPositionMs = clamp(positionMs);
        this.paused = paused;
        this.startedAtMs = paused ? 0 : clock.getAsLong() + Math.max(0, delayMs) - this.pausedPositionMs;
        this.active = true;
    }

    public void stop() { active = false; durationMs = 0; startedAtMs = 0; pausedPositionMs = 0; paused = false; }
    public void pause() { if (!active || paused) return; pausedPositionMs = positionMs(); paused = true; }
    public void resume() { if (!active || !paused) return; startedAtMs = clock.getAsLong() - pausedPositionMs; paused = false; }
    public long positionMs() { return !active ? 0 : paused ? pausedPositionMs : clamp(Math.max(0, clock.getAsLong() - startedAtMs)); }
    public long startedAtMs() { return startedAtMs; }
    public long durationMs() { return durationMs; }
    public long pausedPositionMs() { return pausedPositionMs; }
    public boolean paused() { return paused; }
    public boolean active() { return active; }
    public boolean ended() { return active && !paused && positionMs() >= durationMs; }

    private long clamp(long value) { return Math.min(Math.max(0, value), Math.max(0, durationMs)); }
}
