package stonytark.jammarr.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrServer {
    private static final JammarrServer INSTANCE = new JammarrServer();
    private GlobalPlayer player;

    public static JammarrServer instance() { return INSTANCE; }
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            JammarrNetwork.activeServer(server);
            try { INSTANCE.player = new GlobalPlayer(server); }
            catch (Exception error) { Jammarr.LOGGER.error("Unable to initialize Jammarr", error); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (INSTANCE.player != null) { INSTANCE.player.close(); INSTANCE.player = null; }
            JammarrNetwork.activeServer(null);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (INSTANCE.player != null) INSTANCE.player.tick(); });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (INSTANCE.player != null) INSTANCE.player.playerJoined(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (INSTANCE.player != null) INSTANCE.player.playerLeft(handler.player);
        });
    }

    public void hello(ServerPlayer sender) { if (player != null) player.hello(sender); }
    public void browse(ServerPlayer sender, JammarrPayloads.BrowseRequest request) { if (player != null) player.browse(sender, request); }
    public void queue(ServerPlayer sender, JammarrPayloads.QueueRequest request) { if (player != null) player.queue(sender, request); }
    public void control(ServerPlayer sender, JammarrPayloads.ControlRequest request) { if (player != null) player.control(sender, request); }
    public void station(ServerPlayer sender, JammarrPayloads.StationRequest request) { if (player != null) player.station(sender, request); }
    public void chunks(ServerPlayer sender, JammarrPayloads.ChunkRequest request) { if (player != null) player.chunks(sender, request); }
    public void acknowledge(ServerPlayer sender, JammarrPayloads.ChunkAcknowledgement value) { if (player != null) player.acknowledge(sender, value); }
    public void health(ServerPlayer sender, JammarrPayloads.AudioHealth value) { if (player != null) player.health(sender, value); }
    public void sync(ServerPlayer sender) { if (player != null) player.sync(sender); }
    public GlobalPlayer player() { return player; }
}
