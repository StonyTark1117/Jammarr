package stonytark.jammarr.network;

import net.legacyfabric.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.legacyfabric.fabric.api.networking.v1.PacketByteBufs;
import net.legacyfabric.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.legacyfabric.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.PacketByteBuf;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.util.UUID;

/** Legacy Fabric 1.8.9 adapter for the canonical protocol-6 codecs. */
public final class LegacyNetwork {
    public interface ServerListener {
        void accept(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message);
    }
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    public static final String CHANNEL = "jammarr";
    private static final ClientCapabilityRegistry<UUID> CAPABILITIES =
            new ClientCapabilityRegistry<UUID>(ProtocolLimits.serverHelloTimeoutMs());
    private static volatile ServerListener serverListener;
    private static volatile ClientListener clientListener;
    private static volatile boolean serverAvailable;
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, handler, buffer, responseSender) -> {
            final LegacyEnvelope envelope;
            final Object message;
            try {
                envelope = LegacyEnvelope.read(buffer);
                message = envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND);
            } catch (ProtocolException malformed) {
                server.submit(() -> handler.disconnect("Malformed Jammarr packet"));
                return;
            }
            server.submit(() -> handleServer(player, envelope.type(), message));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CAPABILITIES.connected(handler.player.getUuid(), System.currentTimeMillis(),
                        ServerPlayNetworking.canSend(handler.player, CHANNEL)));
        registered = true;
    }

    public static void setServerListener(ServerListener listener) { serverListener = listener; }
    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static void clientConnected(boolean available) { serverAvailable = available; }
    public static void clientDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static <T> void sendToPlayer(ServerPlayerEntity player, LegacyPacketTypes.Type<T> type, T message) {
        if (!accepted(player) || !ServerPlayNetworking.canSend(player, CHANNEL)) return;
        PacketByteBuf buffer = PacketByteBufs.create();
        LegacyEnvelope.encode(type, message).write(buffer);
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }

    public static void receiveClient(PacketByteBuf buffer) {
        LegacyEnvelope envelope = LegacyEnvelope.read(buffer);
        Object message = envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND);
        ClientListener listener = clientListener;
        if (listener != null) listener.accept(envelope.type(), message);
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        if (!serverAvailable) return;
        PacketByteBuf buffer = PacketByteBufs.create();
        LegacyEnvelope.encode(type, message).write(buffer);
        ClientPlayNetworking.send(CHANNEL, buffer);
    }

    private static void handleServer(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) message;
            if (!CAPABILITIES.accept(player.getUuid(), hello.protocolVersion(), Jammarr.PROTOCOL)) {
                player.networkHandler.disconnect("Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL);
                return;
            }
            ProtocolCapabilities.Negotiated negotiated = ProtocolCapabilities.negotiate(
                    hello.features(), hello.audioChunkBytes(), hello.chunksPerRequest());
            sendToPlayer(player, LegacyPacketTypes.SERVER_HELLO,
                    new ControlPackets.ServerHello(Jammarr.PROTOCOL, System.currentTimeMillis(),
                            negotiated.features(), negotiated.audioChunkBytes(), negotiated.chunksPerRequest()));
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
        return player != null && CAPABILITIES.capable(player.getUuid());
    }
    public static void playerLeft(ServerPlayerEntity player) {
        if (player != null) CAPABILITIES.remove(player.getUuid());
    }
    public static synchronized void shutdown() {
        CAPABILITIES.clear();
        serverListener = null;
        clientListener = null;
    }

    private LegacyNetwork() {}
}
