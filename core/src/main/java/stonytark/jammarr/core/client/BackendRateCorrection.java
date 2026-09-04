package stonytark.jammarr.core.client;

/** Keeps small output-clock differences from accumulating into a stream restart. */
public final class BackendRateCorrection {
    private static final double DEADBAND_MS = 20;
    private static final double CORRECTION_INTERVAL_MS = 10_000;
    private static final double MAX_RATE_CHANGE = 0.01;

    public static float pitch(long driftMillis) {
        double magnitude = Math.max(0, Math.abs((double) driftMillis) - DEADBAND_MS);
        double correction = Math.min(MAX_RATE_CHANGE, magnitude / CORRECTION_INTERVAL_MS);
        return (float) (1.0 + (driftMillis < 0 ? correction : -correction));
    }

    private BackendRateCorrection() {}
}
