package stonytark.jammarr.core.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stonytark.jammarr.core.model.RestartMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test void importsLegacyOnceWithoutModifyingSourceAndNormalizesBounds() throws Exception {
        Path canonical = temporary.resolve("world/serverconfig/jammarr-server.toml");
        Path legacy = temporary.resolve("config/jammarr-server-fabric.toml");
        Files.createDirectories(legacy.getParent());
        String source = "plexUrl = \"https://plex.lan:32400/\" # old loader\n"
                + "plexToken = \"private-token\"\nmusicLibrary = \"Music\"\n"
                + "restartMode = \"resume-position\"\npauseWhenNoPlayers = false\n"
                + "operatorPermissionLevel = 99\nqueueLimit = -8\naudioBitrateKbps = 999\n"
                + "cacheSizeMiB = 2\nstationMetadataFallbackEnabled = true\nunknown = \"ignored\"\n";
        Files.write(legacy, source.getBytes(StandardCharsets.UTF_8));

        CanonicalConfigFiles.ServerConfig imported = CanonicalConfigFiles.loadServer(canonical, legacy);
        assertEquals(legacy, imported.importedFrom());
        assertEquals("https://plex.lan:32400", imported.plexUrl());
        assertEquals("private-token", imported.plexToken());
        assertEquals(RestartMode.RESUME_POSITION, imported.restartMode());
        assertFalse(imported.pauseWhenEmpty());
        assertEquals(4, imported.operatorPermissionLevel());
        assertEquals(1, imported.queueLimit());
        assertEquals(320, imported.audioBitrateKbps());
        assertEquals(64, imported.cacheSizeMiB());
        assertTrue(imported.stationMetadataFallbackEnabled());
        assertEquals(source, new String(Files.readAllBytes(legacy), StandardCharsets.UTF_8));

        Files.write(legacy, "queueLimit = 2\n".getBytes(StandardCharsets.UTF_8));
        CanonicalConfigFiles.ServerConfig loadedAgain = CanonicalConfigFiles.loadServer(canonical, legacy);
        assertNull(loadedAgain.importedFrom());
        assertEquals(1, loadedAgain.queueLimit(), "canonical file wins after one-time import");
        assertFalse(new String(Files.readAllBytes(canonical), StandardCharsets.UTF_8).contains("unknown"));
    }

    @Test void rejectsInvalidServerScalarsAndCredentialsInUrl() throws Exception {
        Path canonical = temporary.resolve("jammarr-server.toml");
        Files.write(canonical, ("plexUrl = \"http://user:pass@plex.lan:32400\"\n"
                + "restartMode = \"bogus\"\npauseWhenNoPlayers = perhaps\n"
                + "operatorPermissionLevel = nope\n").getBytes(StandardCharsets.UTF_8));
        CanonicalConfigFiles.ServerConfig config = CanonicalConfigFiles.loadServer(canonical);
        assertEquals("http://127.0.0.1:32400", config.plexUrl());
        assertEquals(RestartMode.RESTART_TRACK, config.restartMode());
        assertTrue(config.pauseWhenEmpty());
        assertEquals(2, config.operatorPermissionLevel());
    }

    @Test void clientSettingsPersistAtomicallyAndClamp() throws Exception {
        Path canonical = temporary.resolve("config/jammarr-client.toml");
        Path legacy = temporary.resolve("config/jammarr-client-fabric.toml");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, "enabled = false\nvolume = 4.5\n".getBytes(StandardCharsets.UTF_8));
        CanonicalConfigFiles.ClientConfig config = CanonicalConfigFiles.loadClient(canonical, legacy);
        assertFalse(config.enabled());
        assertEquals(1.0, config.volume());
        assertEquals(legacy, config.importedFrom());

        config.enabled(true);
        config.volume(0.25);
        config.saveVolume();
        CanonicalConfigFiles.ClientConfig restored = CanonicalConfigFiles.loadClient(canonical, legacy);
        assertTrue(restored.enabled());
        assertEquals(0.25, restored.volume());
        assertNull(restored.importedFrom());
    }
}
