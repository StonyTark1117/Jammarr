package stonytark.jammarr.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrServer {
    private static volatile JammarrServer INSTANCE;
    private GlobalPlayer player;

    public JammarrServer() { INSTANCE = this; }
    public static JammarrServer instance() { return INSTANCE; }

    @SubscribeEvent public void started(ServerStartedEvent event) {
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
            player = new GlobalPlayer(event.getServer());
        }
        catch (Exception error) { throw new IllegalStateException("Unable to initialize Jammarr", error); }
    }
    @SubscribeEvent public void stopping(ServerStoppingEvent event) { if (player != null) { player.close(); player = null; } }
    @SubscribeEvent public void tick(ServerTickEvent.Post event) { if (player != null) player.tick(); }
    @SubscribeEvent public void joined(PlayerEvent.PlayerLoggedInEvent event) { if (player != null && event.getEntity() instanceof ServerPlayer serverPlayer) player.playerJoined(serverPlayer); }
    @SubscribeEvent public void left(PlayerEvent.PlayerLoggedOutEvent event) { if (player != null && event.getEntity() instanceof ServerPlayer serverPlayer) player.playerLeft(serverPlayer); }

    public void hello(ServerPlayer sender) { if (player != null) player.hello(sender); }
    public void browse(ServerPlayer sender, JammarrPayloads.BrowseRequest request) { if (player != null) player.browse(sender, request); }
    public void queue(ServerPlayer sender, JammarrPayloads.QueueRequest request) { if (player != null) player.queue(sender, request); }
    public void control(ServerPlayer sender, JammarrPayloads.ControlRequest request) { if (player != null) player.control(sender, request); }
    public void station(ServerPlayer sender, JammarrPayloads.StationRequest request) { if (player != null) player.station(sender, request); }
    public void chunks(ServerPlayer sender, JammarrPayloads.ChunkRequest request) { if (player != null) player.chunks(sender, request); }
    public void acknowledge(ServerPlayer sender, JammarrPayloads.ChunkAcknowledgement acknowledgement) { if (player != null) player.acknowledge(sender, acknowledgement); }
    public void health(ServerPlayer sender, JammarrPayloads.AudioHealth health) { if (player != null) player.health(sender, health); }
    public void sync(ServerPlayer sender) { if (player != null) player.sync(sender); }
    public GlobalPlayer player() { return player; }
}
