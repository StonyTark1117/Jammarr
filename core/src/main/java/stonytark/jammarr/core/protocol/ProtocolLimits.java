package stonytark.jammarr.core.protocol;

public final class ProtocolLimits {
    public static final int VERSION = 6;
    public static final String ACCEPTANCE_ENABLED_PROPERTY = "jammarr.acceptance.enabled";
    public static final String ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY = "jammarr.acceptance.clientProtocol";
    public static final String ACCEPTANCE_SUPPRESS_HELLO_PROPERTY = "jammarr.acceptance.suppressClientHello";
    public static final String ACCEPTANCE_CLIENT_HELLO_DELAY_MS_PROPERTY = "jammarr.acceptance.clientHelloDelayMs";
    public static final String ACCEPTANCE_COMMAND_PROBE_PROPERTY = "jammarr.acceptance.commandProbe";
    public static final String ACCEPTANCE_AUDIO_PROBE_PROPERTY = "jammarr.acceptance.audioProbe";
    public static final String ACCEPTANCE_AUDIO_LEADER_PROPERTY = "jammarr.acceptance.audioLeader";
    public static final String ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY = "jammarr.acceptance.audioControlFile";
    public static final String ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY = "jammarr.acceptance.helloTimeoutMs";
    public static final long SERVER_HELLO_TIMEOUT_MS = 60_000L;
    public static final int MAX_BROWSE_RESULTS = 50;
    public static final int MAX_STATION_SEEDS = 5;
    public static final int MAX_PLAYBACK_ENTRIES = 504;
    public static final int MAX_STATION_PREVIEW = 3;
    public static final int MAX_ADVENTURE_PATH = 100;
    public static final int MAX_AUDIO_CHUNK_BYTES = 16_384;
    public static final int MAX_AUDIO_CHUNKS = 65_536;
    public static final long MAX_AUDIO_DURATION_MS = 3L * 60L * 60L * 1_000L;

    /**
     * Returns the protocol advertised by a real client during release acceptance.
     * Production behavior remains fixed at protocol 6 unless the acceptance gate
     * is explicitly enabled and supplies a non-negative integer override.
     */
    public static int clientHelloVersion() {
        if (!Boolean.getBoolean(ACCEPTANCE_ENABLED_PROPERTY)) return VERSION;
        String configured = System.getProperty(ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY);
        if (configured == null) return VERSION;
        try {
            int parsed = Integer.parseInt(configured);
            return parsed < 0 ? VERSION : parsed;
        } catch (NumberFormatException ignored) {
            return VERSION;
        }
    }

    /** Allows a real client to exercise the server's missing-hello timeout. */
    public static boolean clientHelloSuppressed() {
        return Boolean.getBoolean(ACCEPTANCE_ENABLED_PROPERTY)
                && Boolean.getBoolean(ACCEPTANCE_SUPPRESS_HELLO_PROPERTY);
    }

    /** Delays the real client hello only when the acceptance harness is enabled. */
    public static long clientHelloDelayMs() {
        if (!Boolean.getBoolean(ACCEPTANCE_ENABLED_PROPERTY)) return 0L;
        String configured = System.getProperty(ACCEPTANCE_CLIENT_HELLO_DELAY_MS_PROPERTY);
        if (configured == null) return 0L;
        try {
            long parsed = Long.parseLong(configured);
            return parsed < 0L || parsed >= SERVER_HELLO_TIMEOUT_MS ? 0L : parsed;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** Enables real-client command-tree and diagnostics acceptance checks. */
    public static boolean commandProbeEnabled() {
        return Boolean.getBoolean(ACCEPTANCE_ENABLED_PROPERTY)
                && Boolean.getBoolean(ACCEPTANCE_COMMAND_PROBE_PROPERTY);
    }

    /** Enables deterministic real-client audio acceptance behavior. */
    public static boolean audioProbeEnabled() {
        return Boolean.getBoolean(ACCEPTANCE_ENABLED_PROPERTY)
                && Boolean.getBoolean(ACCEPTANCE_AUDIO_PROBE_PROPERTY);
    }

    public static boolean audioProbeLeader() {
        return audioProbeEnabled() && Boolean.getBoolean(ACCEPTANCE_AUDIO_LEADER_PROPERTY);
    }

    /** Returns a control file only inside the explicitly enabled audio gate. */
    public static String audioControlFile() {
        if (!audioProbeEnabled()) return "";
        String configured = System.getProperty(ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY);
        return configured == null ? "" : configured.trim();
    }

    /**
     * Returns the server-side application hello deadline. Production always
     * allows 60 seconds; release acceptance may select a shorter positive
     * deadline so a deliberately silent real client does not stall the gate.
     */
    public static long serverHelloTimeoutMs() {
        if (!Boolean.getBoolean(ACCEPTANCE_ENABLED_PROPERTY)) return SERVER_HELLO_TIMEOUT_MS;
        String configured = System.getProperty(ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY);
        if (configured == null) return SERVER_HELLO_TIMEOUT_MS;
        try {
            long parsed = Long.parseLong(configured);
            return parsed < 1L || parsed > SERVER_HELLO_TIMEOUT_MS ? SERVER_HELLO_TIMEOUT_MS : parsed;
        } catch (NumberFormatException ignored) {
            return SERVER_HELLO_TIMEOUT_MS;
        }
    }

    /** Converts the wall-clock policy to 20 Hz server ticks, rounding up. */
    public static long serverHelloTimeoutTicks() {
        long timeoutMs = serverHelloTimeoutMs();
        return Math.max(1L, (timeoutMs + 49L) / 50L);
    }

    private ProtocolLimits() {}
}
