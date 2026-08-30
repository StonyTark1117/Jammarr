package stonytark.jammarr.server;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.network.packet.PacketHelper;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.LegacyEnvelope;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Server-only half of the Babric transport, isolated from physical clients. */
public final class BabricServerNetwork {
    public interface ServerListener {
        void accept(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message);
    }

    private static final ClientCapabilityRegistry<UUID> CAPABILITIES =
            new ClientCapabilityRegistry<UUID>(ProtocolLimits.serverHelloTimeoutMs());
    private static volatile ServerListener serverListener;

    public static void setServerListener(ServerListener listener) { serverListener = listener; }

    public static void receive(PlayerEntity player, MessagePacket packet) {
        if (!(player instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        try {
            LegacyEnvelope envelope = LegacyEnvelope.read(packet == null ? null : packet.bytes);
            Object message = envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND);
            handle(serverPlayer, envelope.type(), message);
        } catch (ProtocolException malformed) {
            serverPlayer.networkHandler.disconnect("Malformed Jammarr packet");
        }
    }

    public static void playerConnected(ServerPlayerEntity player) {
        if (player != null) CAPABILITIES.connected(playerId(player), System.currentTimeMillis(), true);
    }

    public static <T> void sendToPlayer(ServerPlayerEntity player, LegacyPacketTypes.Type<T> type, T message) {
        if (!accepted(player)) return;
        MessagePacket packet = new MessagePacket(LegacyNetwork.CHANNEL);
        packet.bytes = LegacyEnvelope.encode(type, message).toByteArray();
        PacketHelper.sendTo(player, packet);
    }

    private static void handle(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) message;
            if (!CAPABILITIES.accept(playerId(player), hello.protocolVersion(), Jammarr.PROTOCOL)) {
                String reason = "Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL;
                Jammarr.LOGGER.warn(reason);
                player.networkHandler.disconnect(reason);
                return;
            }
            ProtocolCapabilities.Negotiated negotiated = ProtocolCapabilities.negotiate(
                    hello.features(), hello.audioChunkBytes(), hello.chunksPerRequest());
            sendToPlayer(player, LegacyPacketTypes.SERVER_HELLO,
                    new ControlPackets.ServerHello(Jammarr.PROTOCOL, System.currentTimeMillis(),
                            negotiated.features(), negotiated.audioChunkBytes(), negotiated.chunksPerRequest()));
            Jammarr.LOGGER.info("Established Jammarr protocol {} with {}", Jammarr.PROTOCOL, player.name);
            ServerListener listener = serverListener;
            if (listener != null) listener.accept(player, type, message);
            return;
        }
        if (!accepted(player)) {
            player.networkHandler.disconnect("Jammarr protocol hello is required before play packets");
            return;
        }
        if (type == LegacyPacketTypes.TIME_SYNC_REQUEST) {
            ControlPackets.TimeSyncRequest request = (ControlPackets.TimeSyncRequest) message;
            sendToPlayer(player, LegacyPacketTypes.TIME_SYNC_RESPONSE,
                    new ControlPackets.TimeSyncResponse(request.nonce(), request.clientSentEpochMs(), System.currentTimeMillis()));
            return;
        }
        ServerListener listener = serverListener;
        if (listener != null) listener.accept(player, type, message);
    }

    public static void serverTick() { CAPABILITIES.expire(System.currentTimeMillis()); }
    public static boolean accepted(ServerPlayerEntity player) {
        return player != null && CAPABILITIES.capable(playerId(player));
    }
    public static void playerLeft(ServerPlayerEntity player) {
        if (player != null) CAPABILITIES.remove(playerId(player));
    }
    public static synchronized void shutdown() {
        CAPABILITIES.clear();
        serverListener = null;
    }
    public static UUID playerId(ServerPlayerEntity player) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.name).getBytes(StandardCharsets.UTF_8));
    }

    private BabricServerNetwork() {}
}
