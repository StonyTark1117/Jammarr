package stonytark.jammarr;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.Logger;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.client.LegacyClient;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.server.LegacyCommands;
import stonytark.jammarr.server.LegacyGlobalPlayer;
import stonytark.jammarr.server.LegacySavedData;

import java.io.IOException;
import java.util.Map;

@Mod(
        modid = Jammarr.MOD_ID,
        name = Jammarr.MOD_NAME,
        version = Jammarr.VERSION,
        acceptableRemoteVersions = "*",
        guiFactory = "stonytark.jammarr.client.LegacyGuiFactory"
)
public final class Jammarr {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.1.0";
    public static final int PROTOCOL = 6;

    public static Logger LOGGER;
    private static LegacyGlobalPlayer coordinator;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
        LOGGER.info("Initializing Jammarr {} for Forge 1.12.2 protocol {}", VERSION, PROTOCOL);
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
        FMLCommonHandler.instance().bus().register(this);
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

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && coordinator != null) coordinator.tick();
    }

    @SubscribeEvent
    public void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (coordinator != null && event.player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            coordinator.playerLeft((net.minecraft.entity.player.EntityPlayerMP) event.player);
        }
    }

    @NetworkCheckHandler
    public boolean acceptOptionalPeer(Map<String, String> remoteVersions, Side remoteSide) {
        // Capability and protocol negotiation happens after login. This permits both a vanilla
        // client on a Jammarr server and a Jammarr client on an unmodded server.
        return true;
    }
}
