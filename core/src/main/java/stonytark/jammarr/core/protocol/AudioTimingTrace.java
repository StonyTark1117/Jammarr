package stonytark.jammarr.core.protocol;

/** Monotonic, acceptance-only audio pipeline markers retained in client/server logs. */
public final class AudioTimingTrace {
    public static void record(String stage, Object... fields) {
        if (!ProtocolLimits.audioProbeEnabled()) return;
        StringBuilder value = new StringBuilder("JAMMARR_AUDIO_TIMING stage=").append(safe(stage))
                .append(" monotonicNanos=").append(System.nanoTime());
        for (int index = 0; index + 1 < fields.length; index += 2) {
            value.append(' ').append(safe(fields[index])).append('=').append(safe(fields[index + 1]));
        }
        System.out.println(value.toString());
    }

    private static String safe(Object value) {
        return String.valueOf(value).replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    private AudioTimingTrace() {}
}
