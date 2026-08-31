package stonytark.jammarr.config;

import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;

import java.io.File;
import java.io.IOException;

/** Installs only the canonical client configuration in a LiteLoader companion. */
public final class LegacyConfig {
    public static CanonicalConfigFiles.ClientConfig installClient(File configDirectory) throws IOException {
        CanonicalConfigFiles.ClientConfig values = CanonicalConfigFiles.loadClientForLoader(
                configDirectory.toPath(), "legacy");
        JammarrSettings.installClient(values);
        return values;
    }

    private LegacyConfig() {}
}
