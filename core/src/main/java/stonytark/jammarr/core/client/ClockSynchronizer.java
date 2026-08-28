package stonytark.jammarr.core.client;

/** Estimates server wall-clock offset using NTP-style request/response timestamps. */
public final class ClockSynchronizer {
    public static final int STARTUP_SAMPLE_TARGET = 10;

    private boolean initialized;
    private long offsetMs;
    private long bestRoundTripMs = Long.MAX_VALUE;
    private int samples;

    public synchronized Sample accept(long clientSentMs, long serverReceivedMs, long clientReceivedMs) {
        long roundTrip = Math.max(0, clientReceivedMs - clientSentMs);
        long midpoint = clientSentMs + roundTrip / 2;
        long estimate = serverReceivedMs - midpoint;
        if (!initialized || roundTrip < bestRoundTripMs) {
            offsetMs = estimate;
            initialized = true;
        }
        bestRoundTripMs = Math.min(bestRoundTripMs, roundTrip);
        samples++;
        return new Sample(roundTrip, estimate, offsetMs);
    }

    public synchronized long toLocalTime(long serverEpochMs) { return serverEpochMs - offsetMs; }
    public synchronized long toServerTime(long clientEpochMs) { return clientEpochMs + offsetMs; }
    public synchronized long offsetMs() { return offsetMs; }
    public synchronized boolean initialized() { return initialized; }
    public synchronized boolean readyForPlayback() { return initialized && samples >= STARTUP_SAMPLE_TARGET; }
    public synchronized int sampleCount() { return samples; }
    public synchronized void reset() { initialized = false; offsetMs = 0; bestRoundTripMs = Long.MAX_VALUE; samples = 0; }

    public static final class Sample {
        private final long roundTripMs;
        private final long rawOffsetMs;
        private final long filteredOffsetMs;

        public Sample(long roundTripMs, long rawOffsetMs, long filteredOffsetMs) {
            this.roundTripMs = roundTripMs;
            this.rawOffsetMs = rawOffsetMs;
            this.filteredOffsetMs = filteredOffsetMs;
        }

        public long roundTripMs() { return roundTripMs; }
        public long rawOffsetMs() { return rawOffsetMs; }
        public long filteredOffsetMs() { return filteredOffsetMs; }
    }
}
