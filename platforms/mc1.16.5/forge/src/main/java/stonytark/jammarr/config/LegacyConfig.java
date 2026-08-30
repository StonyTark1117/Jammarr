package stonytark.jammarr.config;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.fml.loading.FMLPaths;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Installs the canonical config while retaining the Java-8 platform adapter. */
public final class LegacyConfig {
    public static CanonicalConfigFiles.ClientConfig installClient(File ignored) throws IOException {
        Path configDirectory = FMLPaths.CONFIGDIR.get();
        CanonicalConfigFiles.ClientConfig values = CanonicalConfigFiles.loadClientForLoader(configDirectory, "forge");
        JammarrSettings.installClient(values);
        return values;
    }

    public static CanonicalConfigFiles.ServerConfig installServer(MinecraftServer server) throws IOException {
        Path configDirectory = FMLPaths.CONFIGDIR.get();
        Path canonical = server.getWorldPath(FolderName.ROOT).resolve("serverconfig")
                .resolve(CanonicalConfigFiles.SERVER_FILE_NAME);
        CanonicalConfigFiles.ServerConfig values = CanonicalConfigFiles.loadServerForLoader(
                canonical, configDirectory, "forge");
        JammarrSettings.installServer(values);
        return values;
    }
    private LegacyConfig() {}
}
