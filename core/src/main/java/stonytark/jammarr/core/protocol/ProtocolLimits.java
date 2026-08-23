package stonytark.jammarr.core.protocol;

public final class ProtocolLimits {
    public static final int VERSION = 5;
    public static final int MAX_BROWSE_RESULTS = 50;
    public static final int MAX_STATION_SEEDS = 5;
    public static final int MAX_PLAYBACK_ENTRIES = 504;
    public static final int MAX_STATION_PREVIEW = 3;
    public static final int MAX_ADVENTURE_PATH = 100;
    public static final int MAX_AUDIO_CHUNK_BYTES = 16_384;

    private ProtocolLimits() {}
}
