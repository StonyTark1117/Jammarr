package stonytark.jammarr;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.server.JammarrCommands;
import stonytark.jammarr.server.JammarrServer;
import org.slf4j.Logger;

@Mod(Jammarr.MODID)
public final class Jammarr {
    public static final String MODID = "jammarr";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jammarr(IEventBus modBus, ModContainer container) {
        migrateClientConfig("neoforge");
        JammarrSettings.installClient(JammarrConfig.clientValues());
        container.registerConfig(ModConfig.Type.CLIENT, JammarrConfig.CLIENT_SPEC);
        modBus.addListener(JammarrNetwork::register);
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.register(new JammarrServer());
        gameBus.register(new JammarrCommands());
    }

    private static void migrateClientConfig(String loader) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try { CanonicalConfigFiles.loadClientForLoader(FMLPaths.CONFIGDIR.get(), loader); }
        catch (Exception error) { throw new IllegalStateException("Unable to migrate Jammarr client settings", error); }
    }
}
