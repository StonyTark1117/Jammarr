package stonytark.jammarr.core.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stonytark.jammarr.core.model.RestartMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalConfigFilesTest {
    @TempDir Path temporary;

    @Test void createsValidatedServerDefaults() throws Exception {
        Path canonical = temporary.resolve("world/serverconfig/jammarr-server.toml");
        CanonicalConfigFiles.ServerConfig config = CanonicalConfigFiles.loadServer(canonical);
        assertTrue(Files.isRegularFile(canonical));
        assertEquals("http://127.0.0.1:32400", config.plexUrl());
        assertEquals(RestartMode.RESTART_TRACK, config.restartMode());
        assertEquals(500, config.queueLimit());
        assertNull(config.importedFrom());
    }

    @Test void importsLegacyOnceWithoutModifyingSource() throws Exception {
        Path canonical = temporary.resolve("world/serverconfig/jammarr-server.toml");
        Path legacy = temporary.resolve("config/jammarr-server-fabric.toml");
        Files.createDirectories(legacy.getParent());
        String source = "plexUrl = \"https://plex.lan:32400/\" # old loader\n"
                + "plexToken = \"private-token\"\nmusicLibrary = \"Music\"\n"
                + "restartMode = \"resume-position\"\npauseWhenNoPlayers = false\n"
                + "operatorPermissionLevel = 4\nqueueLimit = 8\naudioBitrateKbps = 320\n"
                + "cacheSizeMiB = 64\nstationMetadataFallbackEnabled = true\nunknown = \"ignored\"\n";
        Files.write(legacy, source.getBytes(StandardCharsets.UTF_8));

        CanonicalConfigFiles.ServerConfig imported = CanonicalConfigFiles.loadServer(canonical, legacy);
        assertEquals(legacy, imported.importedFrom());
        assertEquals("https://plex.lan:32400", imported.plexUrl());
        assertEquals("private-token", imported.plexToken());
        assertEquals(RestartMode.RESUME_POSITION, imported.restartMode());
        assertFalse(imported.pauseWhenEmpty());
        assertEquals(4, imported.operatorPermissionLevel());
        assertEquals(8, imported.queueLimit());
        assertEquals(320, imported.audioBitrateKbps());
        assertEquals(64, imported.cacheSizeMiB());
        assertTrue(imported.stationMetadataFallbackEnabled());
        assertEquals(source, new String(Files.readAllBytes(legacy), StandardCharsets.UTF_8));

        Files.write(legacy, "queueLimit = 2\n".getBytes(StandardCharsets.UTF_8));
        CanonicalConfigFiles.ServerConfig loadedAgain = CanonicalConfigFiles.loadServer(canonical, legacy);
        assertNull(loadedAgain.importedFrom());
        assertEquals(8, loadedAgain.queueLimit(), "canonical file wins after one-time import");
        assertFalse(new String(Files.readAllBytes(canonical), StandardCharsets.UTF_8).contains("unknown"));
    }

    @Test void rejectsInvalidServerValuesWithoutLeakingOrRewritingThem() throws Exception {
        String[] invalidLines = new String[] {
                "plexUrl = \"http://private-user:private-pass@plex.lan:32400\"\n",
                "plexUrl = \"http://plex.lan:32400?X-Plex-Token=private-token\"\n",
                "restartMode = \"bogus\"\n",
                "pauseWhenNoPlayers = perhaps\n",
                "operatorPermissionLevel = nope\n",
                "operatorPermissionLevel = 5\n",
                "queueLimit = 0\n",
                "audioBitrateKbps = 321\n",
                "cacheSizeMiB = 63\n",
                "stationMetadataFallbackEnabled = perhaps\n",
                "plexToken = \"unterminated\n"
        };
        for (int index = 0; index < invalidLines.length; index++) {
            final String invalidLine = invalidLines[index];
            Path canonical = temporary.resolve("invalid-" + index + ".toml");
            byte[] original = invalidLine.getBytes(StandardCharsets.UTF_8);
            Files.write(canonical, original);
            CanonicalConfigFiles.ConfigValidationException error = assertThrows(
                    CanonicalConfigFiles.ConfigValidationException.class,
                    () -> CanonicalConfigFiles.loadServer(canonical), invalidLine);
            assertFalse(error.getMessage().contains("private"), invalidLine);
            assertArrayEquals(original, Files.readAllBytes(canonical), invalidLine);
        }
    }

    @Test void clientSettingsPersistAtomically() throws Exception {
        Path canonical = temporary.resolve("config/jammarr-client.toml");
        Path legacy = temporary.resolve("config/jammarr-client-fabric.toml");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, "enabled = false\nvolume = 0.75\n".getBytes(StandardCharsets.UTF_8));
        CanonicalConfigFiles.ClientConfig config = CanonicalConfigFiles.loadClient(canonical, legacy);
        assertFalse(config.enabled());
        assertEquals(0.75, config.volume());
        assertEquals(legacy, config.importedFrom());

        config.enabled(true);
        config.volume(0.25);
        config.saveVolume();
        CanonicalConfigFiles.ClientConfig restored = CanonicalConfigFiles.loadClient(canonical, legacy);
        assertTrue(restored.enabled());
        assertEquals(0.25, restored.volume());
        assertNull(restored.importedFrom());
    }

    @Test void rejectsInvalidClientSettingsWithoutRewritingThem() throws Exception {
        String[] invalid = {"enabled = perhaps\n", "volume = 1.01\n", "volume = nan\n"};
        for (int index = 0; index < invalid.length; index++) {
            final String invalidValue = invalid[index];
            Path canonical = temporary.resolve("invalid-client-" + index + ".toml");
            byte[] original = invalidValue.getBytes(StandardCharsets.UTF_8);
            Files.write(canonical, original);
            assertThrows(CanonicalConfigFiles.ConfigValidationException.class,
                    () -> CanonicalConfigFiles.loadClient(canonical), invalidValue);
            assertArrayEquals(original, Files.readAllBytes(canonical), invalidValue);
        }
    }

    @Test void importsNeoForgeFabricForgeAndLegacyFixturesWithoutChangingThem() throws Exception {
        Fixture[] fixtures = new Fixture[] {
                new Fixture("neoforge-server.toml", "https://neo.plex.lan:32400", "neo-token", "Neo Music",
                        RestartMode.RESUME_POSITION, false, 3, 321, 192, 2_048, true),
                new Fixture("fabric-server.toml", "http://fabric.plex.lan:32400", "fabric-token", "Fabric Music",
                        RestartMode.RESUME_POSITION, false, 1, 111, 160, 1_024, true),
                new Fixture("forge-server.toml", "http://forge.plex.lan:32400", "forge-token", "Forge Music",
                        RestartMode.RESTART_TRACK, true, 2, 222, 128, 512, false),
                new Fixture("legacy-server.toml", "http://legacy.plex.lan:32400", "legacy-token", "Legacy Music",
                        RestartMode.RESTART_TRACK, true, 4, 77, 96, 256, false)
        };
        for (Fixture fixture : fixtures) {
            Path source = temporary.resolve("sources").resolve(fixture.name);
            Files.createDirectories(source.getParent());
            java.io.InputStream resource = getClass().getResourceAsStream("/config/" + fixture.name);
            assertNotNull(resource, fixture.name);
            try { Files.copy(resource, source, StandardCopyOption.REPLACE_EXISTING); }
            finally { resource.close(); }
            byte[] original = Files.readAllBytes(source);
            Path canonical = temporary.resolve("canonical").resolve(fixture.name)
                    .resolve("world/serverconfig/jammarr-server.toml");

            CanonicalConfigFiles.ServerConfig actual = CanonicalConfigFiles.loadServer(canonical, source);

            assertEquals(fixture.url, actual.plexUrl(), fixture.name);
            assertEquals(fixture.token, actual.plexToken(), fixture.name);
            assertEquals(fixture.library, actual.musicLibrary(), fixture.name);
            assertEquals(fixture.restartMode, actual.restartMode(), fixture.name);
            assertEquals(fixture.pauseWhenEmpty, actual.pauseWhenEmpty(), fixture.name);
            assertEquals(fixture.permission, actual.operatorPermissionLevel(), fixture.name);
            assertEquals(fixture.queueLimit, actual.queueLimit(), fixture.name);
            assertEquals(fixture.bitrate, actual.audioBitrateKbps(), fixture.name);
            assertEquals(fixture.cacheMiB, actual.cacheSizeMiB(), fixture.name);
            assertEquals(fixture.metadataFallback, actual.stationMetadataFallbackEnabled(), fixture.name);
            assertArrayEquals(original, Files.readAllBytes(source), fixture.name + " source changed");
            assertEquals(source, actual.importedFrom(), fixture.name);
        }
    }

    @Test void runtimeDiscoveryImportsAcrossLoadersAndPrefersTheActiveLoader() throws Exception {
        Path configDirectory = temporary.resolve("config");
        Files.createDirectories(configDirectory);
        Files.write(configDirectory.resolve("jammarr-server-fabric.toml"),
                "musicLibrary = \"Fabric Music\"\n".getBytes(StandardCharsets.UTF_8));
        Files.write(configDirectory.resolve("jammarr-server-neoforge.toml"),
                "musicLibrary = \"NeoForge Music\"\n".getBytes(StandardCharsets.UTF_8));
        Path canonical = temporary.resolve("world/serverconfig/jammarr-server.toml");

        CanonicalConfigFiles.ServerConfig server = CanonicalConfigFiles.loadServerForLoader(
                canonical, configDirectory, "neoforge");

        assertEquals("NeoForge Music", server.musicLibrary());
        assertEquals(configDirectory.resolve("jammarr-server-neoforge.toml"), server.importedFrom());

        Path clientDirectory = temporary.resolve("client-config");
        Files.createDirectories(clientDirectory);
        Files.write(clientDirectory.resolve("jammarr-client-forge.toml"),
                "enabled = false\nvolume = 0.4\n".getBytes(StandardCharsets.UTF_8));
        CanonicalConfigFiles.ClientConfig client = CanonicalConfigFiles.loadClientForLoader(
                clientDirectory, "fabric");
        assertFalse(client.enabled());
        assertEquals(0.4, client.volume());
        assertEquals(clientDirectory.resolve("jammarr-client-forge.toml"), client.importedFrom());
    }

    private static final class Fixture {
        private final String name;
        private final String url;
        private final String token;
        private final String library;
        private final RestartMode restartMode;
        private final boolean pauseWhenEmpty;
        private final int permission;
        private final int queueLimit;
        private final int bitrate;
        private final long cacheMiB;
        private final boolean metadataFallback;

        private Fixture(String name, String url, String token, String library, RestartMode restartMode,
                        boolean pauseWhenEmpty, int permission, int queueLimit, int bitrate, long cacheMiB,
                        boolean metadataFallback) {
            this.name = name;
            this.url = url;
            this.token = token;
            this.library = library;
            this.restartMode = restartMode;
            this.pauseWhenEmpty = pauseWhenEmpty;
            this.permission = permission;
            this.queueLimit = queueLimit;
            this.bitrate = bitrate;
            this.cacheMiB = cacheMiB;
            this.metadataFallback = metadataFallback;
        }
    }
}
