package stonytark.jammarr.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.Util;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.CoreLogger;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
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

/** Minecraft 1.16.5 adapter over the shared Java 8 playback coordinator. */
public final class LegacyGlobalPlayer implements AutoCloseable, LegacyNetwork.ServerListener {
    private final Queue<Runnable> mainThreadActions = new ConcurrentLinkedQueue<Runnable>();
    private final GlobalPlaybackCoordinator<ServerPlayer> delegate;

    public LegacyGlobalPlayer(final MinecraftServer server) throws IOException {
        delegate = new GlobalPlaybackCoordinator<ServerPlayer>(new CoordinatorRuntime<ServerPlayer>() {
            @Override public UUID playerId(ServerPlayer player) { return player.getUUID(); }
            @Override public boolean isOperator(ServerPlayer player, int permissionLevel) {
                return player.hasPermissions(permissionLevel);
            }
            @Override public List<ServerPlayer> players() {
                List<ServerPlayer> listeners = new ArrayList<ServerPlayer>();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (LegacyNetwork.accepted(player)) listeners.add(player);
                }
                return listeners;
            }
            @Override public int playerCount() {
                int listeners = 0;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (LegacyNetwork.accepted(player)) listeners++;
                }
                return listeners;
            }
            @Override public int totalPlayerCount() {
                return server.getPlayerList().getPlayers().size();
            }
            @Override public java.nio.file.Path cacheDirectory() { return server.getFile("jammarr-cache").toPath(); }
            @Override public void execute(Runnable action) { mainThreadActions.add(action); }
            @Override public void send(ServerPlayer player, JammarrMessage message) {
                if (LegacyNetwork.accepted(player)) sendCore(player, message);
            }
            @Override public void chat(ServerPlayer player, String message) {
                player.sendMessage(new TextComponent(message), Util.NIL_UUID);
            }
            @Override public CoreLogger logger() {
                return new CoreLogger() {
                    @Override public void info(String message) { Jammarr.LOGGER.info(message); }
                    @Override public void warn(String message, Throwable error) { Jammarr.LOGGER.warn(message, error); }
                };
            }
        }, LegacySavedData.get(server));
        LegacyNetwork.setServerListener(this);
    }

    @Override
    public void accept(ServerPlayer player, LegacyPacketTypes.Type<?> type, Object message) {
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
    public void control(ServerPlayer player, ControlPackets.ControlRequest request) { delegate.control(player, request); }
    public void station(ServerPlayer player, ControlPackets.StationRequest request) { delegate.station(player, request); }
    public void playerLeft(ServerPlayer player) { delegate.playerLeft(player); }
    public long cacheSize() { return delegate.cacheSize(); }
    public String status() { return delegate.status(); }
    public String stationStatus() { return delegate.stationStatus(); }
    public long stationGeneration() { return delegate.stationGeneration(); }
    public String diagnostics() { return delegate.diagnostics(); }

    private static void sendCore(ServerPlayer player, JammarrMessage message) {
        if (message instanceof ControlPackets.BrowseResults) send(player, LegacyPacketTypes.BROWSE_RESULTS, (ControlPackets.BrowseResults) message);
        else if (message instanceof StatePackets.AdventurePreview) send(player, LegacyPacketTypes.ADVENTURE_PREVIEW, (StatePackets.AdventurePreview) message);
        else if (message instanceof TransportPackets.AudioChunk) send(player, LegacyPacketTypes.AUDIO_CHUNK, (TransportPackets.AudioChunk) message);
        else if (message instanceof TransportPackets.AudioManifest) send(player, LegacyPacketTypes.AUDIO_MANIFEST, (TransportPackets.AudioManifest) message);
        else if (message instanceof StatePackets.PlaybackState) send(player, LegacyPacketTypes.PLAYBACK_STATE, (StatePackets.PlaybackState) message);
        else if (message instanceof StatePackets.StationState) send(player, LegacyPacketTypes.STATION_STATE, (StatePackets.StationState) message);
        else if (message instanceof StatePackets.ErrorMessage) send(player, LegacyPacketTypes.ERROR, (StatePackets.ErrorMessage) message);
        else throw new IllegalArgumentException("Unsupported Jammarr clientbound message " + message.getClass().getName());
    }

    private static <T> void send(ServerPlayer player, LegacyPacketTypes.Type<T> type, T message) {
        LegacyNetwork.sendToPlayer(player, type, message);
    }

    @Override public void close() {
        delegate.close();
        LegacyNetwork.setServerListener(null);
        mainThreadActions.clear();
    }
}
