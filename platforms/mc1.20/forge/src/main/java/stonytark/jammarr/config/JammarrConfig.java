package stonytark.jammarr.config;

import net.minecraftforge.common.ForgeConfigSpec;
import stonytark.jammarr.core.model.RestartMode;
import stonytark.jammarr.core.platform.JammarrSettings;

public final class JammarrConfig {
    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.ConfigValue<String> PLEX_URL = SERVER_BUILDER
            .comment("Plex Media Server base URL, for example http://127.0.0.1:32400")
            .translation("jammarr.configuration.plexUrl")
            .define("plexUrl", "http://127.0.0.1:32400");
    public static final ForgeConfigSpec.ConfigValue<String> PLEX_TOKEN = SERVER_BUILDER
            .comment("Plex token. Prefer the JAMMARR_PLEX_TOKEN environment variable.")
            .translation("jammarr.configuration.plexToken").define("plexToken", "");
    public static final ForgeConfigSpec.ConfigValue<String> MUSIC_LIBRARY = SERVER_BUILDER
            .comment("Plex music library title or numeric section key. Blank prefers a library named Music, then falls back to the first valid music library.")
            .translation("jammarr.configuration.musicLibrary").define("musicLibrary", "");
    public static final ForgeConfigSpec.EnumValue<RestartMode> RESTART_MODE = SERVER_BUILDER
            .translation("jammarr.configuration.restartMode").defineEnum("restartMode", RestartMode.RESTART_TRACK);
    public static final ForgeConfigSpec.BooleanValue PAUSE_WHEN_EMPTY = SERVER_BUILDER
            .translation("jammarr.configuration.pauseWhenNoPlayers").define("pauseWhenNoPlayers", true);
    public static final ForgeConfigSpec.IntValue OP_PERMISSION = SERVER_BUILDER
            .translation("jammarr.configuration.operatorPermissionLevel").defineInRange("operatorPermissionLevel", 2, 0, 4);
    public static final ForgeConfigSpec.IntValue QUEUE_LIMIT = SERVER_BUILDER
            .translation("jammarr.configuration.queueLimit").defineInRange("queueLimit", 500, 1, 500);
    public static final ForgeConfigSpec.IntValue BITRATE = SERVER_BUILDER
            .translation("jammarr.configuration.audioBitrateKbps").defineInRange("audioBitrateKbps", 160, 64, 320);
    public static final ForgeConfigSpec.LongValue CACHE_MIB = SERVER_BUILDER
            .translation("jammarr.configuration.cacheSizeMiB").defineInRange("cacheSizeMiB", 1024L, 64L, 16384L);
    public static final ForgeConfigSpec.BooleanValue STATION_METADATA_FALLBACK = SERVER_BUILDER
            .comment("Allow metadata/random fallback when Plex sonic analysis is unavailable.")
            .translation("jammarr.configuration.stationMetadataFallbackEnabled")
            .define("stationMetadataFallbackEnabled", false);
    public static final ForgeConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.BooleanValue ENABLED = CLIENT_BUILDER
            .translation("jammarr.configuration.enabled").define("enabled", true);
    public static final ForgeConfigSpec.DoubleValue VOLUME = CLIENT_BUILDER
            .translation("jammarr.configuration.volume").defineInRange("volume", 1.0, 0.0, 1.0);
    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    private static final JammarrSettings.ServerValues SERVER_VALUES = new JammarrSettings.ServerValues() {
        @Override public String plexUrl() { return PLEX_URL.get(); }
        @Override public String plexToken() { return PLEX_TOKEN.get(); }
        @Override public String musicLibrary() { return MUSIC_LIBRARY.get(); }
        @Override public RestartMode restartMode() { return RESTART_MODE.get(); }
        @Override public boolean pauseWhenEmpty() { return PAUSE_WHEN_EMPTY.get(); }
        @Override public int operatorPermissionLevel() { return OP_PERMISSION.get(); }
        @Override public int queueLimit() { return QUEUE_LIMIT.get(); }
        @Override public int audioBitrateKbps() { return BITRATE.get(); }
        @Override public long cacheSizeMiB() { return CACHE_MIB.get(); }
        @Override public boolean stationMetadataFallbackEnabled() { return STATION_METADATA_FALLBACK.get(); }
    };
    private static final JammarrSettings.ClientValues CLIENT_VALUES = new JammarrSettings.ClientValues() {
        @Override public boolean enabled() { return ENABLED.get(); }
        @Override public void enabled(boolean value) { ENABLED.set(value); }
        @Override public void saveEnabled() { ENABLED.save(); }
        @Override public double volume() { return VOLUME.get(); }
        @Override public void volume(double value) { VOLUME.set(value); }
        @Override public void saveVolume() { VOLUME.save(); }
    };

    public static JammarrSettings.ServerValues serverValues() { return SERVER_VALUES; }
    public static JammarrSettings.ClientValues clientValues() { return CLIENT_VALUES; }
    private JammarrConfig() {}
}
