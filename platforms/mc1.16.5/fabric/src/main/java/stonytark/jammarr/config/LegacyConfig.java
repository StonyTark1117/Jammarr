package stonytark.jammarr.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Installs the canonical config while retaining the Java-8 platform adapter. */
public final class LegacyConfig {
    public static CanonicalConfigFiles.ClientConfig installClient(File ignored) throws IOException {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        CanonicalConfigFiles.ClientConfig values = CanonicalConfigFiles.loadClientForLoader(configDirectory, "fabric");
        JammarrSettings.installClient(values);
        return values;
    }

    public static CanonicalConfigFiles.ServerConfig installServer(MinecraftServer server) throws IOException {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path canonical = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                .resolve(CanonicalConfigFiles.SERVER_FILE_NAME);
        CanonicalConfigFiles.ServerConfig values = CanonicalConfigFiles.loadServerForLoader(
                canonical, configDirectory, "fabric");
        JammarrSettings.installServer(values);
        return values;
    }
    private LegacyConfig() {}
}
