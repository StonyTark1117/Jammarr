package stonytark.jammarr.core.platform;

import stonytark.jammarr.core.model.RestartMode;

/** Loader-neutral validated configuration access installed by each platform adapter. */
public final class JammarrSettings {
    public interface ServerValues {
        String plexUrl();
        String plexToken();
        String musicLibrary();
        RestartMode restartMode();
        boolean pauseWhenEmpty();
        int operatorPermissionLevel();
        int queueLimit();
        int audioBitrateKbps();
        long cacheSizeMiB();
        boolean stationMetadataFallbackEnabled();
    }

    public interface ClientValues {
        boolean enabled();
        void enabled(boolean value);
        void saveEnabled();
        double volume();
        void volume(double value);
        void saveVolume();
    }

    private static volatile ServerValues server = new DefaultServerValues();
    private static volatile ClientValues client = new DefaultClientValues();

    public static void installServer(ServerValues values) {
        if (values == null) throw new IllegalArgumentException("values");
        server = values;
    }

    public static void installClient(ClientValues values) {
        if (values == null) throw new IllegalArgumentException("values");
        client = values;
    }

    public static String plexUrl() { return safe(server.plexUrl(), "http://127.0.0.1:32400"); }
    public static String plexToken() {
        String environment = System.getenv("JAMMARR_PLEX_TOKEN");
        return environment == null || environment.trim().isEmpty() ? safe(server.plexToken(), "").trim() : environment.trim();
    }
    public static String musicLibrary() { return safe(server.musicLibrary(), ""); }
    public static RestartMode restartMode() {
        RestartMode value = server.restartMode();
        return value == null ? RestartMode.RESTART_TRACK : value;
    }
    public static boolean pauseWhenEmpty() { return server.pauseWhenEmpty(); }
    public static int operatorPermissionLevel() { return clamp(server.operatorPermissionLevel(), 0, 4); }
    public static int queueLimit() { return clamp(server.queueLimit(), 1, 500); }
    public static int audioBitrateKbps() { return clamp(server.audioBitrateKbps(), 64, 320); }
    public static long cacheSizeMiB() { return clamp(server.cacheSizeMiB(), 64L, 16_384L); }
    public static boolean stationMetadataFallbackEnabled() { return server.stationMetadataFallbackEnabled(); }
    public static boolean enabled() { return client.enabled(); }
    public static void enabled(boolean value) { client.enabled(value); }
    public static void saveEnabled() { client.saveEnabled(); }
    public static double volume() { return clamp(client.volume(), 0.0, 1.0); }
    public static void volume(double value) { client.volume(clamp(value, 0.0, 1.0)); }
    public static void saveVolume() { client.saveVolume(); }

    private static String safe(String value, String fallback) { return value == null ? fallback : value; }
    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static long clamp(long value, long minimum, long maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }

    private static final class DefaultServerValues implements ServerValues {
        @Override public String plexUrl() { return "http://127.0.0.1:32400"; }
        @Override public String plexToken() { return ""; }
        @Override public String musicLibrary() { return ""; }
        @Override public RestartMode restartMode() { return RestartMode.RESTART_TRACK; }
        @Override public boolean pauseWhenEmpty() { return true; }
        @Override public int operatorPermissionLevel() { return 2; }
        @Override public int queueLimit() { return 500; }
        @Override public int audioBitrateKbps() { return 160; }
        @Override public long cacheSizeMiB() { return 1_024; }
        @Override public boolean stationMetadataFallbackEnabled() { return false; }
    }

    private static final class DefaultClientValues implements ClientValues {
        private boolean enabled = true;
        private double volume = 1.0;
        @Override public boolean enabled() { return enabled; }
        @Override public void enabled(boolean value) { enabled = value; }
        @Override public void saveEnabled() {}
        @Override public double volume() { return volume; }
        @Override public void volume(double value) { volume = value; }
        @Override public void saveVolume() {}
    }

    private JammarrSettings() {}
}
