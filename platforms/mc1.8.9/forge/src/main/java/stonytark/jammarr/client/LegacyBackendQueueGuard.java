package stonytark.jammarr.client;

/** Prevents a stopped legacy source from being restarted with a stale OpenAL queue. */
final class LegacyBackendQueueGuard {
    static boolean shouldRecover(boolean paused, boolean terminal, boolean playing,
                                 long nowMs, long activationGraceUntilMs) {
        return !paused && !terminal && !playing && nowMs >= activationGraceUntilMs;
    }

    private LegacyBackendQueueGuard() {}
}
