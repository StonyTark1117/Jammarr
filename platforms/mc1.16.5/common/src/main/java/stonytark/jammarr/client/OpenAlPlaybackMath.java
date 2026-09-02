package stonytark.jammarr.client;

final class OpenAlPlaybackMath {
    private static final double FIXED_32_SCALE = 4_294_967_296.0;

    static long playedMillis(long submittedFrames, long queuedFrames,
                             long sampleOffsetFixed, long latencyNanos,
                             float sampleRate) {
        if (submittedFrames < 0 || queuedFrames < 0 || queuedFrames > submittedFrames
                || sampleOffsetFixed < 0 || latencyNanos < 0
                || !Float.isFinite(sampleRate) || sampleRate <= 0) return -1;
        double offsetFrames = sampleOffsetFixed / FIXED_32_SCALE;
        if (offsetFrames > queuedFrames) return -1;
        double latencyFrames = latencyNanos * (double) sampleRate / 1_000_000_000.0;
        double heardFrames = submittedFrames - queuedFrames + offsetFrames - latencyFrames;
        return Math.max(0, Math.round(heardFrames * 1_000.0 / sampleRate));
    }

    static long latencyMillis(long latencyNanos) {
        if (latencyNanos < 0) return -1;
        return Math.round(latencyNanos / 1_000_000.0);
    }

    private OpenAlPlaybackMath() {}
}
