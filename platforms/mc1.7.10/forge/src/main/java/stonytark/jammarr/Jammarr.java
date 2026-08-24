package stonytark.jammarr;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;
import org.apache.logging.log4j.Logger;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.server.LegacySavedData;

import java.io.IOException;
import java.util.Map;

@Mod(
        modid = Jammarr.MOD_ID,
        name = Jammarr.MOD_NAME,
        version = Jammarr.VERSION,
        acceptableRemoteVersions = Jammarr.VERSION
)
public final class Jammarr {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.0.0";
    public static final int PROTOCOL = 5;

    public static Logger LOGGER;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
        LOGGER.info("Initializing Jammarr {} for Forge 1.7.10 protocol {}", VERSION, PROTOCOL);
        if (event.getSide().isClient()) {
            try {
                LegacyConfig.installClient(event.getModConfigurationDirectory());
            } catch (IOException error) {
                throw new IllegalStateException("Unable to load canonical Jammarr client configuration", error);
            }
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LegacyNetwork.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        try {
            LegacyConfig.installServer(event.getServer());
            LegacySavedData.get(event.getServer());
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load canonical Jammarr server configuration", error);
        }
        // The legacy command and server coordinator are registered in this lifecycle phase.
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        LegacyNetwork.shutdown();
        // Release all legacy HTTP, cache, and OpenAL state on shutdown.
    }

    @NetworkCheckHandler
    public boolean requireMatchingClient(Map<String, String> remoteVersions, Side remoteSide) {
        return remoteVersions != null && VERSION.equals(remoteVersions.get(MOD_ID));
    }
}
