package stonytark.jammarr.client;

/** Requires repeated backend-clock divergence before disrupting playback. */
final class BackendDriftGuard {
    private final long thresholdMillis;
    private final int requiredSamples;
    private int consecutiveSamples;
    private int direction;

    BackendDriftGuard(long thresholdMillis, int requiredSamples) {
        if (thresholdMillis < 0 || requiredSamples < 1) throw new IllegalArgumentException();
        this.thresholdMillis = thresholdMillis;
        this.requiredSamples = requiredSamples;
    }

    boolean observe(long driftMillis) {
        if (driftMillis <= thresholdMillis && driftMillis >= -thresholdMillis) {
            reset();
            return false;
        }
        int observedDirection = driftMillis > 0 ? 1 : -1;
        if (direction != observedDirection) {
            direction = observedDirection;
            consecutiveSamples = 0;
        }
        return ++consecutiveSamples >= requiredSamples;
    }

    void reset() { consecutiveSamples = 0; direction = 0; }
}
