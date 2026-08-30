package stonytark.jammarr;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.network.NetworkMod;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import stonytark.jammarr.client.LegacyClient;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.server.LegacyCommands;
import stonytark.jammarr.server.LegacyGlobalPlayer;
import stonytark.jammarr.server.LegacySavedData;

import java.io.IOException;

@Mod(modid = Jammarr.MOD_ID, name = Jammarr.MOD_NAME, version = Jammarr.VERSION)
@NetworkMod(clientSideRequired = false, serverSideRequired = false)
public final class Jammarr {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.1.0";
    public static final int PROTOCOL = 6;

    public static LegacyLogger LOGGER;
    private static LegacyGlobalPlayer coordinator;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = new LegacyLogger(event.getModLog());
        LOGGER.info("Initializing Jammarr {} for Forge 1.6.4 protocol {}", VERSION, PROTOCOL);
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
        if (event.getSide().isClient()) LegacyClient.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        try {
            LegacyConfig.installServer(event.getServer());
            LegacySavedData.get(event.getServer());
            coordinator = new LegacyGlobalPlayer(event.getServer());
            event.registerServerCommand(new LegacyCommands(coordinator));
        } catch (IOException error) {
            // Forge 1.6.4 records a server-starting mod error without reliably
            // ending the main loop. Explicitly fail closed so no misconfigured
            // server remains reachable after FML rejects Jammarr startup.
            event.getServer().initiateShutdown();
            throw new IllegalStateException("Unable to load canonical Jammarr server configuration", error);
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
        LegacyNetwork.shutdown();
    }

    public static void serverTick() {
        if (coordinator != null) coordinator.tick();
    }

    public static void playerLeft(EntityPlayer player) {
        if (coordinator != null && player instanceof EntityPlayerMP) {
            coordinator.playerLeft((EntityPlayerMP) player);
        }
    }
}
