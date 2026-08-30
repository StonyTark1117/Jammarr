package stonytark.jammarr.server;

import net.fabricmc.loader.api.FabricLoader;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.modificationstation.stationapi.api.event.init.InitFinishedEvent;
import net.modificationstation.stationapi.api.event.registry.MessageListenerRegistryEvent;
import net.modificationstation.stationapi.api.event.tick.GameTickEvent;
import net.modificationstation.stationapi.api.server.event.network.PlayerLoginEvent;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;

public final class BabricServerEvents {
    private boolean initializationPending;
    private boolean initializationAttempted;

    @EventListener
    public void ready(InitFinishedEvent event) {
        initializationPending = true;
    }

    @EventListener
    public void registerMessages(MessageListenerRegistryEvent event) {
        Jammarr.LOGGER.info("Registering the Jammarr Beta 1.7.3 server message channel");
        event.register(LegacyNetwork.CHANNEL, BabricServerNetwork::receive);
    }

    @EventListener
    public void tick(GameTickEvent.End event) {
        MinecraftServer server = server();
        if (initializationPending && !initializationAttempted && server.worlds != null) initialize(server);
        BabricServerNetwork.serverTick();
        LegacyGlobalPlayer coordinator = Jammarr.coordinator();
        if (coordinator != null) coordinator.tick();
    }

    private void initialize(MinecraftServer server) {
        initializationAttempted = true;
        try {
            LegacyConfig.installServer(server);
            LegacySavedData.get(server);
            Jammarr.coordinator(new LegacyGlobalPlayer(server));
            initializationPending = false;
        } catch (Exception error) {
            server.stop();
            throw new IllegalStateException("Unable to initialize Jammarr", error);
        }
    }

    @EventListener
    public void login(PlayerLoginEvent event) {
        BabricServerNetwork.playerConnected(event.player);
    }

    public static void playerLeft(ServerPlayerEntity player) {
        BabricServerNetwork.playerLeft(player);
        LegacyGlobalPlayer coordinator = Jammarr.coordinator();
        if (coordinator != null) coordinator.playerLeft(player);
    }

    public static void shutdown() {
        LegacyGlobalPlayer coordinator = Jammarr.coordinator();
        if (coordinator != null) coordinator.close();
        Jammarr.coordinator(null);
        BabricServerNetwork.shutdown();
    }

    private static MinecraftServer server() {
        return (MinecraftServer) FabricLoader.getInstance().getGameInstance();
    }
}
