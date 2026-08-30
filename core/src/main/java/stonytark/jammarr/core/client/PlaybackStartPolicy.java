package stonytark.jammarr.core.client;

/** Shared protection against starting a decoded stream early or without enough PCM to catch up. */
public final class PlaybackStartPolicy {
    public static Decision evaluate(long authoritativePositionMs, long firstDecodedPositionMs,
                                    long bufferedMs, long reserveMs) {
        if (authoritativePositionMs < firstDecodedPositionMs) {
            return new Decision(false, 0L,
                    saturatedDifference(firstDecodedPositionMs, authoritativePositionMs));
        }
        long skipMs = saturatedDifference(authoritativePositionMs, firstDecodedPositionMs);
        long reserve = Math.max(0L, reserveMs);
        boolean representable = skipMs <= Long.MAX_VALUE - reserve;
        long requiredMs = saturatedAdd(skipMs, reserve);
        return new Decision(representable && bufferedMs >= requiredMs, skipMs,
                representable ? Math.max(0L, requiredMs - Math.max(0L, bufferedMs)) : Long.MAX_VALUE);
    }

    public static boolean caughtUp(long requestedSkipMs, long discardedMs, long toleranceMs) {
        return Math.max(0L, requestedSkipMs) - Math.max(0L, discardedMs) <= Math.max(0L, toleranceMs);
    }

    private static long saturatedDifference(long high, long low) {
        if (low < 0L && high > Long.MAX_VALUE + low) return Long.MAX_VALUE;
        return Math.max(0L, high - low);
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    public static final class Decision {
        private final boolean ready;
        private final long skipMs;
        private final long missingMs;

        private Decision(boolean ready, long skipMs, long missingMs) {
            this.ready = ready;
            this.skipMs = skipMs;
            this.missingMs = missingMs;
        }

        public boolean ready() { return ready; }
        public long skipMs() { return skipMs; }
        public long missingMs() { return missingMs; }
    }

    private PlaybackStartPolicy() {}
}
