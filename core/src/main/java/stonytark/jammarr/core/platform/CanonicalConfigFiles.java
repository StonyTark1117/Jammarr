package stonytark.jammarr.core.platform;

import stonytark.jammarr.core.model.RestartMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loader-neutral persistence for Jammarr's canonical TOML files.
 *
 * <p>The parser intentionally accepts only the scalar subset Jammarr writes. Unknown keys and
 * malformed values are ignored, recognized values are bounded, and the canonical file is always
 * rewritten in a deterministic form. A legacy source is consulted only while the canonical file
 * is absent and is never modified.</p>
 */
public final class CanonicalConfigFiles {
    public static final String SERVER_FILE_NAME = "jammarr-server.toml";
    public static final String CLIENT_FILE_NAME = "jammarr-client.toml";

    public static ServerConfig loadServer(Path canonical, Path... legacyCandidates) throws IOException {
        Path source = chooseSource(canonical, legacyCandidates);
        Map<String, String> values = source == null ? Collections.<String, String>emptyMap() : parse(source);
        ServerConfig config = new ServerConfig(canonical,
                validatedUrl(string(values, "plexUrl", "http://127.0.0.1:32400")),
                bounded(string(values, "plexToken", ""), 4_096),
                bounded(string(values, "musicLibrary", ""), 256),
                restartMode(values.get(normalize("restartMode"))),
                bool(values, "pauseWhenNoPlayers", true),
                integer(values, "operatorPermissionLevel", 2, 0, 4),
                integer(values, "queueLimit", 500, 1, 500),
                integer(values, "audioBitrateKbps", 160, 64, 320),
                integer(values, "cacheSizeMiB", 1_024, 64, 16_384),
                bool(values, "stationMetadataFallbackEnabled", false), source != null && !source.equals(canonical) ? source : null);
        config.save();
        secure(canonical);
        return config;
    }

    public static ClientConfig loadClient(Path canonical, Path... legacyCandidates) throws IOException {
        Path source = chooseSource(canonical, legacyCandidates);
        Map<String, String> values = source == null ? Collections.<String, String>emptyMap() : parse(source);
        ClientConfig config = new ClientConfig(canonical, bool(values, "enabled", true),
                decimal(values, "volume", 1.0, 0.0, 1.0), source != null && !source.equals(canonical) ? source : null);
        config.save();
        return config;
    }

    public static final class ServerConfig implements JammarrSettings.ServerValues {
        private final Path path;
        private final String plexUrl;
        private final String plexToken;
        private final String musicLibrary;
        private final RestartMode restartMode;
        private final boolean pauseWhenEmpty;
        private final int operatorPermissionLevel;
        private final int queueLimit;
        private final int audioBitrateKbps;
        private final long cacheSizeMiB;
        private final boolean stationMetadataFallbackEnabled;
        private final Path importedFrom;

        private ServerConfig(Path path, String plexUrl, String plexToken, String musicLibrary,
                             RestartMode restartMode, boolean pauseWhenEmpty, int operatorPermissionLevel,
                             int queueLimit, int audioBitrateKbps, long cacheSizeMiB,
                             boolean stationMetadataFallbackEnabled, Path importedFrom) {
            this.path = path;
            this.plexUrl = plexUrl;
            this.plexToken = plexToken;
            this.musicLibrary = musicLibrary;
            this.restartMode = restartMode;
            this.pauseWhenEmpty = pauseWhenEmpty;
            this.operatorPermissionLevel = operatorPermissionLevel;
            this.queueLimit = queueLimit;
            this.audioBitrateKbps = audioBitrateKbps;
            this.cacheSizeMiB = cacheSizeMiB;
            this.stationMetadataFallbackEnabled = stationMetadataFallbackEnabled;
            this.importedFrom = importedFrom;
        }

        public Path path() { return path; }
        public Path importedFrom() { return importedFrom; }
        @Override public String plexUrl() { return plexUrl; }
        @Override public String plexToken() { return plexToken; }
        @Override public String musicLibrary() { return musicLibrary; }
        @Override public RestartMode restartMode() { return restartMode; }
        @Override public boolean pauseWhenEmpty() { return pauseWhenEmpty; }
        @Override public int operatorPermissionLevel() { return operatorPermissionLevel; }
        @Override public int queueLimit() { return queueLimit; }
        @Override public int audioBitrateKbps() { return audioBitrateKbps; }
        @Override public long cacheSizeMiB() { return cacheSizeMiB; }
        @Override public boolean stationMetadataFallbackEnabled() { return stationMetadataFallbackEnabled; }

        public void save() throws IOException {
            List<String> lines = new ArrayList<String>();
            lines.add("# Jammarr server configuration. JAMMARR_PLEX_TOKEN overrides plexToken.");
            lines.add("plexUrl = " + quoted(plexUrl));
            lines.add("plexToken = " + quoted(plexToken));
            lines.add("musicLibrary = " + quoted(musicLibrary));
            lines.add("restartMode = " + quoted(restartMode.name()));
            lines.add("pauseWhenNoPlayers = " + pauseWhenEmpty);
            lines.add("operatorPermissionLevel = " + operatorPermissionLevel);
            lines.add("queueLimit = " + queueLimit);
            lines.add("audioBitrateKbps = " + audioBitrateKbps);
            lines.add("cacheSizeMiB = " + cacheSizeMiB);
            lines.add("stationMetadataFallbackEnabled = " + stationMetadataFallbackEnabled);
            write(path, lines);
        }
    }

    public static final class ClientConfig implements JammarrSettings.ClientValues {
        private final Path path;
        private final Path importedFrom;
        private boolean enabled;
        private double volume;

        private ClientConfig(Path path, boolean enabled, double volume, Path importedFrom) {
            this.path = path;
            this.enabled = enabled;
            this.volume = volume;
            this.importedFrom = importedFrom;
        }

        public Path path() { return path; }
        public Path importedFrom() { return importedFrom; }
        @Override public synchronized boolean enabled() { return enabled; }
        @Override public synchronized void enabled(boolean value) { enabled = value; }
        @Override public void saveEnabled() { saveUnchecked(); }
        @Override public synchronized double volume() { return volume; }
        @Override public synchronized void volume(double value) { volume = clamp(value, 0.0, 1.0); }
        @Override public void saveVolume() { saveUnchecked(); }

        public synchronized void save() throws IOException {
            write(path, Arrays.asList("# Jammarr local client settings.", "enabled = " + enabled,
                    "volume = " + number(volume)));
        }

        private void saveUnchecked() {
            try { save(); }
            catch (IOException error) { throw new IllegalStateException("Unable to save Jammarr client configuration", error); }
        }
    }

    private static Path chooseSource(Path canonical, Path[] legacyCandidates) {
        if (Files.isRegularFile(canonical)) return canonical;
        if (legacyCandidates != null) for (Path candidate : legacyCandidates) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static Map<String, String> parse(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripComment(line).trim();
                if (line.isEmpty() || line.charAt(0) == '[') continue;
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String key = normalize(line.substring(0, equals));
                if (!recognized(key)) continue;
                values.put(key, scalar(line.substring(equals + 1).trim()));
            }
        } finally { reader.close(); }
        return values;
    }

    private static boolean recognized(String key) {
        return SERVER_KEYS.contains(key) || CLIENT_KEYS.contains(key);
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quoted && value == '\\') { escaped = true; continue; }
            if (value == '"') quoted = !quoted;
            else if (value == '#' && !quoted) return line.substring(0, index);
        }
        return line;
    }

    private static String scalar(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            StringBuilder decoded = new StringBuilder();
            boolean escaped = false;
            for (int index = 1; index < value.length() - 1; index++) {
                char character = value.charAt(index);
                if (escaped) {
                    if (character == 'n') decoded.append('\n');
                    else if (character == 'r') decoded.append('\r');
                    else if (character == 't') decoded.append('\t');
                    else decoded.append(character);
                    escaped = false;
                } else if (character == '\\') escaped = true;
                else decoded.append(character);
            }
            if (escaped) decoded.append('\\');
            return decoded.toString();
        }
        return value;
    }

    private static String string(Map<String, String> values, String key, String fallback) {
        String value = values.get(normalize(key));
        return value == null ? fallback : value.trim();
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(normalize(key));
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private static int integer(Map<String, String> values, String key, int fallback, int minimum, int maximum) {
        String value = values.get(normalize(key));
        try { return value == null ? fallback : clamp(Integer.parseInt(value.replace("_", "")), minimum, maximum); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static double decimal(Map<String, String> values, String key, double fallback, double minimum, double maximum) {
        String value = values.get(normalize(key));
        try {
            double parsed = value == null ? fallback : Double.parseDouble(value.replace("_", ""));
            return Double.isFinite(parsed) ? clamp(parsed, minimum, maximum) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private static RestartMode restartMode(String value) {
        if (value == null) return RestartMode.RESTART_TRACK;
        try { return RestartMode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')); }
        catch (IllegalArgumentException ignored) { return RestartMode.RESTART_TRACK; }
    }

    private static String validatedUrl(String value) {
        String bounded = bounded(value, 2_048);
        try {
            URI uri = new URI(bounded);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null) {
                return bounded.endsWith("/") ? bounded.substring(0, bounded.length() - 1) : bounded;
            }
        } catch (URISyntaxException ignored) {}
        return "http://127.0.0.1:32400";
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String normalize(String key) {
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < key.length(); index++) {
            char value = Character.toLowerCase(key.charAt(index));
            if (Character.isLetterOrDigit(value)) normalized.append(value);
        }
        return normalized.toString();
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }

    private static String number(double value) {
        if (value == Math.rint(value)) return Long.toString((long) value) + ".0";
        return Double.toString(value);
    }

    private static void write(Path path, List<String> lines) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
            try {
                for (String line : lines) { writer.write(line); writer.newLine(); }
            } finally { writer.close(); }
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void secure(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException ignored) {
            // Filesystems without POSIX permissions still receive the canonical file.
        } catch (UnsupportedOperationException ignored) {}
    }

    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }

    private static final List<String> SERVER_KEYS = Arrays.asList(normalize("plexUrl"), normalize("plexToken"),
            normalize("musicLibrary"), normalize("restartMode"), normalize("pauseWhenNoPlayers"),
            normalize("operatorPermissionLevel"), normalize("queueLimit"), normalize("audioBitrateKbps"),
            normalize("cacheSizeMiB"), normalize("stationMetadataFallbackEnabled"));
    private static final List<String> CLIENT_KEYS = Arrays.asList(normalize("enabled"), normalize("volume"));

    private CanonicalConfigFiles() {}
}
