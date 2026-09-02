package stonytark.jammarr.client;

/** Requires repeated backend-clock divergence before disrupting playback. */
final class BackendDriftGuard {
    private final long thresholdMillis;
    private final int requiredSamples;
    private final long minimumPersistenceMillis;
    private int consecutiveSamples;
    private int direction;
    private long firstObservedAtMillis;

    BackendDriftGuard(long thresholdMillis, int requiredSamples, long minimumPersistenceMillis) {
        if (thresholdMillis < 0 || requiredSamples < 1 || minimumPersistenceMillis < 0) {
            throw new IllegalArgumentException();
        }
        this.thresholdMillis = thresholdMillis;
        this.requiredSamples = requiredSamples;
        this.minimumPersistenceMillis = minimumPersistenceMillis;
    }

    boolean observe(long driftMillis, long observedAtMillis) {
        if (driftMillis <= thresholdMillis && driftMillis >= -thresholdMillis) {
            reset();
            return false;
        }
        int observedDirection = driftMillis > 0 ? 1 : -1;
        if (direction != observedDirection) {
            direction = observedDirection;
            consecutiveSamples = 0;
            firstObservedAtMillis = observedAtMillis;
        }
        consecutiveSamples++;
        // OpenAL can return two internally inconsistent source-position
        // snapshots back-to-back while rotating streaming buffers. A sample
        // count alone turned that short probe artifact into an audible
        // rebuffer. Require divergence to persist in monotonic time as well.
        long persistedMillis = Math.max(0, observedAtMillis - firstObservedAtMillis);
        return consecutiveSamples >= requiredSamples
                && persistedMillis >= minimumPersistenceMillis;
    }

    void reset() {
        consecutiveSamples = 0;
        direction = 0;
        firstObservedAtMillis = 0;
    }
}
