package stonytark.jammarr.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.nio.file.Path;
import java.util.UUID;

public final class JammarrServer {
    private static final JammarrServer INSTANCE = new JammarrServer();
    private GlobalPlayer player;
    private final ClientCapabilityRegistry<UUID> capabilities = new ClientCapabilityRegistry<>(ProtocolLimits.serverHelloTimeoutTicks());
    private long ticks;

    public static JammarrServer instance() { return INSTANCE; }
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            JammarrNetwork.activeServer(server);
            INSTANCE.ticks = 0;
            INSTANCE.capabilities.clear();
            try {
                Path configDirectory = FabricLoader.getInstance().getConfigDir();
                Path canonical = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                        .resolve(CanonicalConfigFiles.SERVER_FILE_NAME);
                CanonicalConfigFiles.ServerConfig config = CanonicalConfigFiles.loadServerForLoader(
                        canonical, configDirectory, "fabric");
                JammarrSettings.installServer(config);
                if (config.importedFrom() != null) {
                    Jammarr.LOGGER.info("Imported legacy Jammarr server settings from {}", config.importedFrom());
                }
                INSTANCE.player = new GlobalPlayer(server, INSTANCE::accepted);
            }
            catch (Exception error) { throw new IllegalStateException("Unable to initialize Jammarr", error); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (INSTANCE.player != null) { INSTANCE.player.close(); INSTANCE.player = null; }
            INSTANCE.capabilities.clear();
            JammarrNetwork.activeServer(null);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            INSTANCE.ticks++;
            INSTANCE.capabilities.expire(INSTANCE.ticks);
            if (INSTANCE.player != null) INSTANCE.player.tick();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            INSTANCE.capabilities.connected(handler.player.getUUID(), INSTANCE.ticks,
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                            handler.player, JammarrPayloads.ServerHello.ID));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            INSTANCE.capabilities.remove(handler.player.getUUID());
            if (INSTANCE.player != null) INSTANCE.player.playerLeft(handler.player);
        });
    }

    public void hello(ServerPlayer sender, JammarrPayloads.ClientHello payload) {
        if (capabilities.accept(sender.getUUID(), JammarrNetwork.PROTOCOL, JammarrNetwork.PROTOCOL)
                && player != null) player.hello(sender, payload.features(), payload.audioChunkBytes(), payload.chunksPerRequest());
    }
    public boolean accepted(ServerPlayer sender) { return capabilities.capable(sender.getUUID()); }
    public void browse(ServerPlayer sender, JammarrPayloads.BrowseRequest request) { if (accepted(sender) && player != null) player.browse(sender, request); }
    public void queue(ServerPlayer sender, JammarrPayloads.QueueRequest request) { if (accepted(sender) && player != null) player.queue(sender, request); }
    public void control(ServerPlayer sender, JammarrPayloads.ControlRequest request) { if (accepted(sender) && player != null) player.control(sender, request); }
    public void station(ServerPlayer sender, JammarrPayloads.StationRequest request) { if (accepted(sender) && player != null) player.station(sender, request); }
    public void chunks(ServerPlayer sender, JammarrPayloads.ChunkRequest request) { if (accepted(sender) && player != null) player.chunks(sender, request); }
    public void acknowledge(ServerPlayer sender, JammarrPayloads.ChunkAcknowledgement value) { if (accepted(sender) && player != null) player.acknowledge(sender, value); }
    public void health(ServerPlayer sender, JammarrPayloads.AudioHealth value) { if (accepted(sender) && player != null) player.health(sender, value); }
    public void sync(ServerPlayer sender) { if (accepted(sender) && player != null) player.sync(sender); }
    public GlobalPlayer player() { return player; }
}
