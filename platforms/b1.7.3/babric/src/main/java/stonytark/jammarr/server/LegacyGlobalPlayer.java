package stonytark.jammarr.server;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.CoreLogger;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.core.server.CoordinatorRuntime;
import stonytark.jammarr.core.server.GlobalPlaybackCoordinator;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Ornithe 1.6.4 adapter over the shared Java 8 playback coordinator. */
public final class LegacyGlobalPlayer implements AutoCloseable, BabricServerNetwork.ServerListener {
    private final Queue<Runnable> mainThreadActions = new ConcurrentLinkedQueue<Runnable>();
    private final GlobalPlaybackCoordinator<ServerPlayerEntity> delegate;

    public LegacyGlobalPlayer(final MinecraftServer server) throws IOException {
        delegate = new GlobalPlaybackCoordinator<ServerPlayerEntity>(new CoordinatorRuntime<ServerPlayerEntity>() {
            @Override public UUID playerId(ServerPlayerEntity player) { return BabricServerNetwork.playerId(player); }
            @Override public boolean isOperator(ServerPlayerEntity player, int permissionLevel) {
                return server.playerManager.isOperator(player.name);
            }
            @SuppressWarnings("unchecked")
            @Override public List<ServerPlayerEntity> players() {
                List<ServerPlayerEntity> listeners = new ArrayList<ServerPlayerEntity>();
                for (ServerPlayerEntity player : (List<ServerPlayerEntity>) server.playerManager.players) {
                    if (BabricServerNetwork.accepted(player)) listeners.add(player);
                }
                return listeners;
            }
            @SuppressWarnings("unchecked")
            @Override public int playerCount() {
                int listeners = 0;
                for (ServerPlayerEntity player : (List<ServerPlayerEntity>) server.playerManager.players) {
                    if (BabricServerNetwork.accepted(player)) listeners++;
                }
                return listeners;
            }
            @SuppressWarnings("unchecked")
            @Override public int totalPlayerCount() {
                return ((List<ServerPlayerEntity>) server.playerManager.players).size();
            }
            @Override public java.nio.file.Path cacheDirectory() { return server.getFile("jammarr-cache").toPath(); }
            @Override public void execute(Runnable action) { mainThreadActions.add(action); }
            @Override public void send(ServerPlayerEntity player, JammarrMessage message) {
                if (BabricServerNetwork.accepted(player)) sendCore(player, message);
            }
            @Override public void chat(ServerPlayerEntity player, String message) {
                player.sendMessage(message);
            }
            @Override public CoreLogger logger() {
                return new CoreLogger() {
                    @Override public void info(String message) { Jammarr.LOGGER.info(message); }
                    @Override public void warn(String message, Throwable error) { Jammarr.LOGGER.warn(message, error); }
                };
            }
        }, LegacySavedData.get(server));
        BabricServerNetwork.setServerListener(this);
    }

    @Override
    public void accept(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message) {
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
    public void control(ServerPlayerEntity player, ControlPackets.ControlRequest request) { delegate.control(player, request); }
    public void station(ServerPlayerEntity player, ControlPackets.StationRequest request) { delegate.station(player, request); }
    public void playerLeft(ServerPlayerEntity player) { delegate.playerLeft(player); }
    public long cacheSize() { return delegate.cacheSize(); }
    public String status() { return delegate.status(); }
    public String stationStatus() { return delegate.stationStatus(); }
    public long stationGeneration() { return delegate.stationGeneration(); }
    public String diagnostics() { return delegate.diagnostics(); }

    private static void sendCore(ServerPlayerEntity player, JammarrMessage message) {
        if (message instanceof ControlPackets.BrowseResults) send(player, LegacyPacketTypes.BROWSE_RESULTS, (ControlPackets.BrowseResults) message);
        else if (message instanceof StatePackets.AdventurePreview) send(player, LegacyPacketTypes.ADVENTURE_PREVIEW, (StatePackets.AdventurePreview) message);
        else if (message instanceof TransportPackets.AudioChunk) send(player, LegacyPacketTypes.AUDIO_CHUNK, (TransportPackets.AudioChunk) message);
        else if (message instanceof TransportPackets.AudioManifest) send(player, LegacyPacketTypes.AUDIO_MANIFEST, (TransportPackets.AudioManifest) message);
        else if (message instanceof StatePackets.PlaybackState) send(player, LegacyPacketTypes.PLAYBACK_STATE, (StatePackets.PlaybackState) message);
        else if (message instanceof StatePackets.StationState) send(player, LegacyPacketTypes.STATION_STATE, (StatePackets.StationState) message);
        else if (message instanceof StatePackets.ErrorMessage) send(player, LegacyPacketTypes.ERROR, (StatePackets.ErrorMessage) message);
        else throw new IllegalArgumentException("Unsupported Jammarr clientbound message " + message.getClass().getName());
    }

    private static <T> void send(ServerPlayerEntity player, LegacyPacketTypes.Type<T> type, T message) {
        BabricServerNetwork.sendToPlayer(player, type, message);
    }

    @Override public void close() {
        delegate.close();
        BabricServerNetwork.setServerListener(null);
        mainThreadActions.clear();
    }
}
