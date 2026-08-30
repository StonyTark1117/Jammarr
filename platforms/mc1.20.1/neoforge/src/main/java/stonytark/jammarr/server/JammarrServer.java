package stonytark.jammarr.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.UUID;

public final class JammarrServer {
    private static final JammarrServer INSTANCE = new JammarrServer();
    private GlobalPlayer player;
    private final ClientCapabilityRegistry<UUID> capabilities = new ClientCapabilityRegistry<>(
            stonytark.jammarr.core.protocol.ProtocolLimits.serverHelloTimeoutTicks());
    private long ticks;

    public static JammarrServer instance() { return INSTANCE; }
    public static void register() { MinecraftForge.EVENT_BUS.register(INSTANCE); }

    @SubscribeEvent public void started(ServerStartedEvent event) {
        ticks = 0L;
        capabilities.clear();
        try {
            java.nio.file.Path configDirectory = FMLPaths.CONFIGDIR.get();
            java.nio.file.Path canonical = event.getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("serverconfig").resolve(CanonicalConfigFiles.SERVER_FILE_NAME);
            CanonicalConfigFiles.ServerConfig config = CanonicalConfigFiles.loadServerForLoader(
                    canonical, configDirectory, "neoforge");
            JammarrSettings.installServer(config);
            if (config.importedFrom() != null) {
                Jammarr.LOGGER.info("Imported legacy Jammarr server settings from {}", config.importedFrom());
            }
            player = new GlobalPlayer(event.getServer(), this::accepted);
        }
        catch (Exception error) { throw new IllegalStateException("Unable to initialize Jammarr", error); }
    }
    @SubscribeEvent public void stopping(ServerStoppingEvent event) {
        if (player != null) { player.close(); player = null; }
        capabilities.clear();
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ticks++;
            capabilities.expire(ticks);
            if (player != null) player.tick();
        }
    }
    @SubscribeEvent public void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) capabilities.connected(serverPlayer.getUUID(), ticks, true);
    }
    @SubscribeEvent public void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            capabilities.remove(serverPlayer.getUUID());
            if (player != null) player.playerLeft(serverPlayer);
        }
    }

    public void hello(ServerPlayer sender) {
        if (capabilities.accept(sender.getUUID(), stonytark.jammarr.network.JammarrNetwork.PROTOCOL,
                stonytark.jammarr.network.JammarrNetwork.PROTOCOL) && player != null) player.hello(sender);
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
