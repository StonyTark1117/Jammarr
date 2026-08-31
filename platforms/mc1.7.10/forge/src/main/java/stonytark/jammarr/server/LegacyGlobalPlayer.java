package stonytark.jammarr.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.DamageSource;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.CoreLogger;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.server.CoordinatorRuntime;
import stonytark.jammarr.core.server.GlobalPlaybackCoordinator;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Forge 1.7.10 adapter over the shared Java 8 playback coordinator. */
public final class LegacyGlobalPlayer implements AutoCloseable, LegacyNetwork.ServerListener {
    private final Queue<Runnable> mainThreadActions = new ConcurrentLinkedQueue<Runnable>();
    private final GlobalPlaybackCoordinator<EntityPlayerMP> delegate;
    private final LegacySavedData savedData;
    private final MinecraftServer server;

    public LegacyGlobalPlayer(final MinecraftServer server) throws IOException {
        this.server = server;
        savedData = LegacySavedData.get(server);
        if (Boolean.getBoolean("jammarr.acceptance.persistenceRead")) {
            verifyAcceptancePersistenceFixture();
        }
        delegate = new GlobalPlaybackCoordinator<EntityPlayerMP>(new CoordinatorRuntime<EntityPlayerMP>() {
            @Override public UUID playerId(EntityPlayerMP player) { return player.getUniqueID(); }
            @Override public boolean isOperator(EntityPlayerMP player, int permissionLevel) {
                return player.canCommandSenderUseCommand(permissionLevel, "jammarr");
            }
            @SuppressWarnings("unchecked")
            @Override public List<EntityPlayerMP> players() {
                List<EntityPlayerMP> listeners = new ArrayList<EntityPlayerMP>();
                for (EntityPlayerMP player : (List<EntityPlayerMP>) server.getConfigurationManager().playerEntityList) {
                    if (LegacyNetwork.accepted(player)) listeners.add(player);
                }
                return listeners;
            }
            @SuppressWarnings("unchecked")
            @Override public int playerCount() {
                int listeners = 0;
                for (EntityPlayerMP player : (List<EntityPlayerMP>) server.getConfigurationManager().playerEntityList) {
                    if (LegacyNetwork.accepted(player)) listeners++;
                }
                return listeners;
            }
            @SuppressWarnings("unchecked")
            @Override public int totalPlayerCount() {
                return ((List<EntityPlayerMP>) server.getConfigurationManager().playerEntityList).size();
            }
            @Override public java.nio.file.Path cacheDirectory() { return server.getFile("jammarr-cache").toPath(); }
            @Override public void execute(Runnable action) { mainThreadActions.add(action); }
            @Override public void send(EntityPlayerMP player, JammarrMessage message) {
                if (LegacyNetwork.accepted(player)) sendCore(player, message);
            }
            @Override public void chat(EntityPlayerMP player, String message) {
                player.addChatMessage(new ChatComponentText(message));
            }
            @Override public CoreLogger logger() {
                return new CoreLogger() {
                    @Override public void info(String message) { Jammarr.LOGGER.info(message); }
                    @Override public void warn(String message, Throwable error) { Jammarr.LOGGER.warn(message, error); }
                };
            }
        }, savedData);
        LegacyNetwork.setServerListener(this);
    }

    @Override
    public void accept(EntityPlayerMP player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) delegate.playerJoined(player);
        else if (type == LegacyPacketTypes.BROWSE_REQUEST) delegate.browse(player, (ControlPackets.BrowseRequest) message);
        else if (type == LegacyPacketTypes.QUEUE_REQUEST) delegate.queue(player, (ControlPackets.QueueRequest) message);
        else if (type == LegacyPacketTypes.CONTROL_REQUEST) delegate.control(player, (ControlPackets.ControlRequest) message);
        else if (type == LegacyPacketTypes.STATION_REQUEST) delegate.station(player, (ControlPackets.StationRequest) message);
        else if (type == LegacyPacketTypes.CHUNK_REQUEST) delegate.chunks(player, (TransportPackets.ChunkRequest) message);
        else if (type == LegacyPacketTypes.CHUNK_ACKNOWLEDGEMENT) delegate.acknowledge(player, (TransportPackets.ChunkAcknowledgement) message);
        else if (type == LegacyPacketTypes.AUDIO_HEALTH) delegate.health(player, (StatePackets.AudioHealth) message);
        else if (type == LegacyPacketTypes.MANIFEST_REQUEST) delegate.sync(player);
    }

    public void tick() {
        Runnable action;
        while ((action = mainThreadActions.poll()) != null) action.run();
        delegate.tick();
    }

    public void validatePlex() { delegate.validatePlex(); }
    public void control(EntityPlayerMP player, ControlPackets.ControlRequest request) { delegate.control(player, request); }
    public void station(EntityPlayerMP player, ControlPackets.StationRequest request) { delegate.station(player, request); }
    public void playerLeft(EntityPlayerMP player) { delegate.playerLeft(player); }
    public long cacheSize() { return delegate.cacheSize(); }
    public String status() { return delegate.status(); }
    public String stationStatus() { return delegate.stationStatus(); }
    public long stationGeneration() { return delegate.stationGeneration(); }
    public String diagnostics() { return delegate.diagnostics(); }

    public void acceptanceDimension(String username, int dimension) {
        if (!Boolean.getBoolean("jammarr.acceptance.enabled")) {
            throw new IllegalStateException("Dimension cycling is acceptance-only");
        }
        EntityPlayerMP player = server.getConfigurationManager().func_152612_a(username);
        if (player == null) throw new IllegalArgumentException("Unknown acceptance player " + username);
        server.getConfigurationManager().transferPlayerToDimension(player, dimension);
        Jammarr.LOGGER.info("Acceptance lifecycle dimension transfer: player={} dimension={}",
                username, dimension);
    }

    public void acceptanceKill(String username) {
        if (!Boolean.getBoolean("jammarr.acceptance.enabled")) {
            throw new IllegalStateException("Death cycling is acceptance-only");
        }
        EntityPlayerMP player = server.getConfigurationManager().func_152612_a(username);
        if (player == null) throw new IllegalArgumentException("Unknown acceptance player " + username);
        player.attackEntityFrom(DamageSource.outOfWorld, Float.MAX_VALUE);
        Jammarr.LOGGER.info("Acceptance lifecycle death triggered: player={}", username);
    }

    public void installAcceptancePersistenceFixture() {
        if (!Boolean.getBoolean("jammarr.acceptance.enabled")) {
            throw new IllegalStateException("Persistence fixtures are acceptance-only");
        }
        savedData.clearAll();
        savedData.queue().add(new QueueTrack("persistence-next", "Persistence Next",
                "Gate Artist", "Gate Album", 12_345L));
        savedData.current(new QueueTrack("persistence-current", "Persistence Current",
                        "Gate Artist", "Gate Album", 23_456L),
                StatePackets.PlaybackOrigin.ADVENTURE, "Persistence Adventure");
        savedData.station(new StationModels.StationDefinition(
                StationModels.StationType.SONIC_ADVENTURE, "Persistence Route",
                java.util.Arrays.asList(
                        new StationModels.StationSeed(StationModels.ItemKind.TRACK,
                                "persistence-current", "Persistence Current", "Gate Artist"),
                        new StationModels.StationSeed(StationModels.ItemKind.TRACK,
                                "persistence-next", "Persistence Next", "Gate Artist")), 17L));
        savedData.autoplayEnabled(true);
        savedData.remember(new QueueTrack("persistence-history", "Persistence History",
                "Gate Artist", "Gate Album", 34_567L));
        savedData.update(4_321L, true);
        // Exercise the stable shared contract through the final reobfuscated
        // class, not only WorldSavedData's MCP-named method.
        savedData.markChanged();
        Jammarr.LOGGER.info("Acceptance schema-4 persistence fixture marked dirty");
    }

    private void verifyAcceptancePersistenceFixture() {
        boolean valid = savedData.current() != null
                && "persistence-current".equals(savedData.current().key())
                && savedData.currentOrigin() == StatePackets.PlaybackOrigin.ADVENTURE
                && "Persistence Adventure".equals(savedData.currentSourceName())
                && savedData.queue().size() == 1
                && "persistence-next".equals(savedData.queue().get(0).key())
                && savedData.station().type() == StationModels.StationType.SONIC_ADVENTURE
                && savedData.station().generation() == 17L
                && savedData.station().seeds().size() == 2
                && savedData.autoplayEnabled()
                && savedData.history().size() == 1
                && "persistence-history".equals(savedData.history().get(0).key())
                && savedData.checkpointMs() == 4_321L
                && savedData.paused();
        if (!valid) throw new IllegalStateException(
                "Production Forge 1.7.10 did not reload the schema-4 persistence fixture");
        Jammarr.LOGGER.info("Acceptance schema-4 persistence fixture reloaded from the production world");
    }

    private static void sendCore(EntityPlayerMP player, JammarrMessage message) {
        if (message instanceof ControlPackets.BrowseResults) send(player, LegacyPacketTypes.BROWSE_RESULTS, (ControlPackets.BrowseResults) message);
        else if (message instanceof StatePackets.AdventurePreview) send(player, LegacyPacketTypes.ADVENTURE_PREVIEW, (StatePackets.AdventurePreview) message);
        else if (message instanceof TransportPackets.AudioChunk) send(player, LegacyPacketTypes.AUDIO_CHUNK, (TransportPackets.AudioChunk) message);
        else if (message instanceof TransportPackets.AudioManifest) send(player, LegacyPacketTypes.AUDIO_MANIFEST, (TransportPackets.AudioManifest) message);
        else if (message instanceof StatePackets.PlaybackState) send(player, LegacyPacketTypes.PLAYBACK_STATE, (StatePackets.PlaybackState) message);
        else if (message instanceof StatePackets.StationState) send(player, LegacyPacketTypes.STATION_STATE, (StatePackets.StationState) message);
        else if (message instanceof StatePackets.ErrorMessage) send(player, LegacyPacketTypes.ERROR, (StatePackets.ErrorMessage) message);
        else throw new IllegalArgumentException("Unsupported Jammarr clientbound message " + message.getClass().getName());
    }

    private static <T> void send(EntityPlayerMP player, LegacyPacketTypes.Type<T> type, T message) {
        LegacyNetwork.sendToPlayer(player, type, message);
    }

    @Override public void close() {
        delegate.close();
        LegacyNetwork.setServerListener(null);
        mainThreadActions.clear();
    }
}
