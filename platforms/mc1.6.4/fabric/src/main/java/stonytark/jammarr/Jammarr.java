package stonytark.jammarr;

import net.fabricmc.api.ModInitializer;
import net.legacyfabric.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.legacyfabric.fabric.api.event.lifecycle.v1.ServerTickEvents;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.server.LegacyCommands;
import stonytark.jammarr.server.LegacyGlobalPlayer;
import stonytark.jammarr.server.LegacySavedData;

public final class Jammarr implements ModInitializer {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.1.0";
    public static final int PROTOCOL = 6;
    public static final LegacyLogger LOGGER = new LegacyLogger(java.util.logging.Logger.getLogger(MOD_ID));
    private static LegacyGlobalPlayer coordinator;

    @Override public void onInitialize() {
        LOGGER.info("Initializing Jammarr {} for Legacy Fabric 1.6.4 protocol {}", VERSION, PROTOCOL);
        LegacyNetwork.register();
        LegacyCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                LegacyConfig.installServer(server);
                LegacySavedData.get(server);
                coordinator = new LegacyGlobalPlayer(server);
            } catch (Exception error) {
                server.stopRunning();
                throw new IllegalStateException("Unable to initialize Jammarr", error);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (coordinator != null) {
                coordinator.close();
                coordinator = null;
            }
            LegacyNetwork.shutdown();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LegacyNetwork.serverTick();
            if (coordinator != null) coordinator.tick();
        });
    }

    public static LegacyGlobalPlayer coordinator() { return coordinator; }
}
