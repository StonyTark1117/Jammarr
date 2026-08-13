package stonytark.pampmod.server;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import stonytark.pampmod.Pampmod;
import stonytark.pampmod.network.PampPayloads;

public final class PampServer {
    private static volatile PampServer INSTANCE;
    private GlobalPlayer player;

    public PampServer() { INSTANCE = this; }
    public static PampServer instance() { return INSTANCE; }

    @SubscribeEvent public void started(ServerStartedEvent event) {
        try { player = new GlobalPlayer(event.getServer()); }
        catch (Exception e) { Pampmod.LOGGER.error("Unable to initialize PAmpMod", e); }
    }
    @SubscribeEvent public void stopping(ServerStoppingEvent event) { if (player != null) { player.close(); player = null; } }
    @SubscribeEvent public void tick(ServerTickEvent.Post event) { if (player != null) player.tick(); }
    @SubscribeEvent public void joined(PlayerEvent.PlayerLoggedInEvent event) { if (player != null && event.getEntity() instanceof ServerPlayer serverPlayer) player.playerJoined(serverPlayer); }
    @SubscribeEvent public void left(PlayerEvent.PlayerLoggedOutEvent event) { if (player != null && event.getEntity() instanceof ServerPlayer serverPlayer) player.playerLeft(serverPlayer); }

    public void hello(ServerPlayer sender) { if (player != null) player.hello(sender); }
    public void browse(ServerPlayer sender, PampPayloads.BrowseRequest request) { if (player != null) player.browse(sender, request); }
    public void queue(ServerPlayer sender, PampPayloads.QueueRequest request) { if (player != null) player.queue(sender, request); }
    public void control(ServerPlayer sender, PampPayloads.ControlRequest request) { if (player != null) player.control(sender, request); }
    public void chunks(ServerPlayer sender, PampPayloads.ChunkRequest request) { if (player != null) player.chunks(sender, request); }
    public void acknowledge(ServerPlayer sender, PampPayloads.ChunkAcknowledgement acknowledgement) { if (player != null) player.acknowledge(sender, acknowledgement); }
    public void sync(ServerPlayer sender) { if (player != null) player.sync(sender); }
    public GlobalPlayer player() { return player; }
}
