package stonytark.jammarr.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class JammarrConfig {
    public enum RestartMode { RESTART_TRACK, CLEAR, RESUME_POSITION }

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.ConfigValue<String> PLEX_URL = SERVER_BUILDER
            .comment("Plex Media Server base URL, for example http://127.0.0.1:32400")
            .define("plexUrl", "http://127.0.0.1:32400");
    public static final ModConfigSpec.ConfigValue<String> PLEX_TOKEN = SERVER_BUILDER
            .comment("Plex token. Prefer the JAMMARR_PLEX_TOKEN environment variable.")
            .define("plexToken", "");
    public static final ModConfigSpec.ConfigValue<String> MUSIC_LIBRARY = SERVER_BUILDER
            .comment("Plex music library title or numeric section key. Blank selects the first music library.")
            .define("musicLibrary", "");
    public static final ModConfigSpec.EnumValue<RestartMode> RESTART_MODE = SERVER_BUILDER
            .defineEnum("restartMode", RestartMode.RESTART_TRACK);
    public static final ModConfigSpec.BooleanValue PAUSE_WHEN_EMPTY = SERVER_BUILDER
            .define("pauseWhenNoPlayers", true);
    public static final ModConfigSpec.IntValue OP_PERMISSION = SERVER_BUILDER
            .defineInRange("operatorPermissionLevel", 2, 0, 4);
    public static final ModConfigSpec.IntValue QUEUE_LIMIT = SERVER_BUILDER
            .comment("Maximum global queue length. Jammarr v1 caps this at 500 tracks.")
            .defineInRange("queueLimit", 500, 1, 500);
    public static final ModConfigSpec.IntValue BITRATE = SERVER_BUILDER
            .comment("Requested Plex MP3 bitrate in kbps.")
            .defineInRange("audioBitrateKbps", 160, 64, 320);
    public static final ModConfigSpec.LongValue CACHE_MIB = SERVER_BUILDER
            .defineInRange("cacheSizeMiB", 1024L, 64L, 16384L);
    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED = CLIENT_BUILDER
            .comment("Listen to the server's Jammarr stream by default.")
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue VOLUME = CLIENT_BUILDER
            .comment("Additional Jammarr volume multiplier.")
            .defineInRange("volume", 1.0, 0.0, 1.0);
    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    private JammarrConfig() {}

    public static String plexToken() {
        String environment = System.getenv("JAMMARR_PLEX_TOKEN");
        return environment == null || environment.isBlank() ? PLEX_TOKEN.get() : environment.trim();
    }
}
