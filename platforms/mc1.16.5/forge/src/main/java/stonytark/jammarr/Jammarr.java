package stonytark.jammarr;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import stonytark.jammarr.client.LegacyClient;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.server.LegacyCommands;
import stonytark.jammarr.server.LegacyGlobalPlayer;
import stonytark.jammarr.server.LegacySavedData;

@Mod(Jammarr.MOD_ID)
public final class Jammarr {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.1.0";
    public static final int PROTOCOL = 6;
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static LegacyGlobalPlayer coordinator;

    public Jammarr() {
        LOGGER.info("Initializing Jammarr {} for Forge 1.16.5 protocol {}", VERSION, PROTOCOL);
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(
                () -> FMLNetworkConstants.IGNORESERVERONLY,
                (remoteVersion, network) -> true));
        LegacyNetwork.register();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(this::serverStarting);
        forgeBus.addListener(this::serverStopped);
        forgeBus.addListener(this::serverTick);
        forgeBus.addListener(this::playerLoggedIn);
        forgeBus.addListener(this::playerLoggedOut);
        forgeBus.addListener(this::registerCommands);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> LegacyClient.register(modBus));
    }

    private void serverStarting(FMLServerStartingEvent event) {
        try {
            LegacyConfig.installServer(event.getServer());
            LegacySavedData.get(event.getServer());
            coordinator = new LegacyGlobalPlayer(event.getServer());
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize Jammarr", error);
        }
    }

    private void serverStopped(FMLServerStoppedEvent event) {
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
        LegacyNetwork.shutdown();
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LegacyNetwork.serverTick();
        if (coordinator != null) coordinator.tick();
    }

    private void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity) {
            LegacyNetwork.playerJoined((ServerPlayerEntity) event.getPlayer());
        }
    }

    private void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        LegacyNetwork.playerLeft(player);
        if (coordinator != null) coordinator.playerLeft(player);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        LegacyCommands.register(event.getDispatcher());
    }

    public static LegacyGlobalPlayer coordinator() { return coordinator; }
}
