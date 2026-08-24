package stonytark.jammarr.core.protocol;

public final class ProtocolLimits {
    public static final int VERSION = 5;
    public static final String ACCEPTANCE_ENABLED_PROPERTY = "jammarr.acceptance.enabled";
    public static final String ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY = "jammarr.acceptance.clientProtocol";
    public static final String ACCEPTANCE_SUPPRESS_HELLO_PROPERTY = "jammarr.acceptance.suppressClientHello";
    public static final String ACCEPTANCE_COMMAND_PROBE_PROPERTY = "jammarr.acceptance.commandProbe";
    public static final String ACCEPTANCE_AUDIO_PROBE_PROPERTY = "jammarr.acceptance.audioProbe";
    public static final String ACCEPTANCE_AUDIO_LEADER_PROPERTY = "jammarr.acceptance.audioLeader";
    public static final String ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY = "jammarr.acceptance.audioControlFile";
    public static final int MAX_BROWSE_RESULTS = 50;
    public static final int MAX_STATION_SEEDS = 5;
    public static final int MAX_PLAYBACK_ENTRIES = 504;
    public static final int MAX_STATION_PREVIEW = 3;
    public static final int MAX_ADVENTURE_PATH = 100;
    public static final int MAX_AUDIO_CHUNK_BYTES = 16_384;

    /**
     * Returns the protocol advertised by a real client during release acceptance.
     * Production behavior remains fixed at protocol 5 unless the acceptance gate
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

    private ProtocolLimits() {}
}
