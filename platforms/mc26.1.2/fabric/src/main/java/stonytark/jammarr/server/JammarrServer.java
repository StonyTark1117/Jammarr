package stonytark.jammarr.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.HelloGate;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.nio.file.Path;
import java.util.UUID;

public final class JammarrServer {
    private static final JammarrServer INSTANCE = new JammarrServer();
    private static final long HELLO_TIMEOUT_TICKS = 100;
    private GlobalPlayer player;
    private final HelloGate<UUID> helloGate = new HelloGate<>(HELLO_TIMEOUT_TICKS);
    private long ticks;

    public static JammarrServer instance() { return INSTANCE; }
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            JammarrNetwork.activeServer(server);
            INSTANCE.ticks = 0;
            INSTANCE.helloGate.clear();
            try {
                Path configDirectory = FabricLoader.getInstance().getConfigDir();
                Path canonical = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                        .resolve(CanonicalConfigFiles.SERVER_FILE_NAME);
                CanonicalConfigFiles.ServerConfig config = CanonicalConfigFiles.loadServer(canonical,
                        configDirectory.resolve(CanonicalConfigFiles.SERVER_FILE_NAME),
                        configDirectory.resolve("jammarr-server-fabric.toml"),
                        configDirectory.resolve("pampmod-server.toml"));
                JammarrSettings.installServer(config);
                if (config.importedFrom() != null) {
                    Jammarr.LOGGER.info("Imported legacy Jammarr server settings from {}", config.importedFrom());
                }
                INSTANCE.player = new GlobalPlayer(server);
            }
            catch (Exception error) { Jammarr.LOGGER.error("Unable to initialize Jammarr", error); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (INSTANCE.player != null) { INSTANCE.player.close(); INSTANCE.player = null; }
            INSTANCE.helloGate.clear();
            JammarrNetwork.activeServer(null);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            INSTANCE.ticks++;
            for (UUID id : INSTANCE.helloGate.expire(INSTANCE.ticks)) {
                ServerPlayer timedOut = server.getPlayerList().getPlayer(id);
                if (timedOut != null) timedOut.connection.disconnect(Component.literal(
                        "Jammarr client handshake timed out; install a compatible Jammarr protocol " + JammarrNetwork.PROTOCOL + " client"));
            }
            if (INSTANCE.player != null) INSTANCE.player.tick();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(handler.player, JammarrPayloads.ServerHello.TYPE)) {
                handler.disconnect(Component.literal("Jammarr is required on the client (protocol " + JammarrNetwork.PROTOCOL + ")"));
                return;
            }
            INSTANCE.helloGate.require(handler.player.getUUID(), INSTANCE.ticks);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            INSTANCE.helloGate.remove(handler.player.getUUID());
            if (INSTANCE.player != null) INSTANCE.player.playerLeft(handler.player);
        });
    }

    public void hello(ServerPlayer sender) {
        if (helloGate.accept(sender.getUUID()) && player != null) player.hello(sender);
    }
    public boolean accepted(ServerPlayer sender) { return helloGate.accepted(sender.getUUID()); }
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
