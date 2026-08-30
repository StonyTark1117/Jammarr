package stonytark.jammarr.config;

import net.minecraft.server.MinecraftServer;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Installs the same canonical configuration files used by every modern target. */
public final class LegacyConfig {
    public static CanonicalConfigFiles.ClientConfig installClient(File configDirectory) throws IOException {
        Path config = configDirectory.toPath();
        CanonicalConfigFiles.ClientConfig values = CanonicalConfigFiles.loadClientForLoader(config, "legacy");
        JammarrSettings.installClient(values);
        return values;
    }

    public static CanonicalConfigFiles.ServerConfig installServer(MinecraftServer server) throws IOException {
        String worldDirectory = server.properties.getProperty("level-name", "world");
        if (worldDirectory == null || worldDirectory.trim().isEmpty()) {
            throw new IOException("Minecraft did not expose the active world directory");
        }
        Path canonical = server.getFile(worldDirectory + "/serverconfig/" + CanonicalConfigFiles.SERVER_FILE_NAME).toPath();
        Path worldServerConfig = canonical.getParent();
        Path gameDirectory = server.getFile(".").toPath();
        Path config = gameDirectory.resolve("config");
        CanonicalConfigFiles.ServerConfig values = CanonicalConfigFiles.loadServer(
                canonical,
                worldServerConfig.resolve("pampmod-server.toml"),
                worldServerConfig.resolve("jammarr-common.toml"),
                config.resolve(CanonicalConfigFiles.SERVER_FILE_NAME),
                config.resolve("jammarr-server-legacy.toml"),
                config.resolve("jammarr-server-forge.toml"),
                config.resolve("jammarr-server-fabric.toml"),
                config.resolve("jammarr-server-neoforge.toml"),
                config.resolve("pampmod-server-legacy.toml"),
                config.resolve("pampmod-server-forge.toml"),
                config.resolve("pampmod-server-fabric.toml"),
                config.resolve("pampmod-server-neoforge.toml"),
                config.resolve("jammarr-common.toml"),
                config.resolve("pampmod-server.toml"),
                config.resolve("pampmod-common.toml"),
                config.resolve("jammarr.toml"),
                config.resolve("pampmod.toml"));
        JammarrSettings.installServer(values);
        return values;
    }

    private LegacyConfig() {}
}
