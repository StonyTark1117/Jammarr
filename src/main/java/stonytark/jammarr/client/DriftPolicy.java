package stonytark.jammarr.client;

public final class DriftPolicy {
    public static final long DEFAULT_REBUFFER_THRESHOLD_MS = 500;
    public static boolean shouldRebuffer(long estimatedPositionMs, long authoritativePositionMs, long thresholdMs) {
        return Math.abs(estimatedPositionMs - authoritativePositionMs) > thresholdMs;
    }
    private DriftPolicy() {}
}
