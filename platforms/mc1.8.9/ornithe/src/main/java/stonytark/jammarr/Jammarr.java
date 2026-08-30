package stonytark.jammarr;

import net.ornithemc.osl.entrypoints.api.ModInitializer;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerEvents;
import net.ornithemc.osl.networking.api.server.ServerConnectionEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static LegacyGlobalPlayer coordinator;

    @Override public void init() {
        LOGGER.info("Initializing Jammarr {} for Ornithe 1.8.9 protocol {}", VERSION, PROTOCOL);
        LegacyNetwork.register();
        MinecraftServerEvents.READY.register(server -> {
            try {
                LegacyConfig.installServer(server);
                LegacySavedData.get(server);
                LegacyCommands.register(server);
                coordinator = new LegacyGlobalPlayer(server);
            } catch (Exception error) {
                server.stop();
                throw new IllegalStateException("Unable to initialize Jammarr", error);
            }
        });
        MinecraftServerEvents.STOP.register(server -> {
            if (coordinator != null) {
                coordinator.close();
                coordinator = null;
            }
            LegacyNetwork.shutdown();
        });
        MinecraftServerEvents.TICK_END.register(server -> {
            LegacyNetwork.serverTick();
            if (coordinator != null) coordinator.tick();
        });
        ServerConnectionEvents.DISCONNECT.register(context -> {
            net.minecraft.server.entity.living.player.ServerPlayerEntity player = context.player();
            LegacyNetwork.playerLeft(player);
            if (coordinator != null) coordinator.playerLeft(player);
        });
    }

    public static LegacyGlobalPlayer coordinator() { return coordinator; }
}
