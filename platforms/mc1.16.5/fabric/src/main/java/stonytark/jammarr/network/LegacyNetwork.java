package stonytark.jammarr.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.TextComponent;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.util.UUID;

/** Fabric/Quilt 1.16.5 adapter for the canonical protocol-6 codecs. */
public final class LegacyNetwork {
    public interface ServerListener {
        void accept(ServerPlayer player, LegacyPacketTypes.Type<?> type, Object message);
    }
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    public static final ResourceLocation CHANNEL = new ResourceLocation(Jammarr.MOD_ID, "play");
    private static final ClientCapabilityRegistry<UUID> CAPABILITIES =
            new ClientCapabilityRegistry<UUID>(ProtocolLimits.serverHelloTimeoutTicks());
    private static volatile ServerListener serverListener;
    private static volatile ClientListener clientListener;
    private static volatile boolean serverAvailable;
    private static long ticks;
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
                server.execute(() -> player.connection.disconnect(new TextComponent("Malformed Jammarr packet")));
                return;
            }
            server.execute(() -> handleServer(player, envelope.type(), message));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CAPABILITIES.connected(handler.player.getUUID(), ticks,
                        ServerPlayNetworking.canSend(handler.player, CHANNEL)));
        registered = true;
    }

    public static void setServerListener(ServerListener listener) { serverListener = listener; }
    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static void clientConnected(boolean available) { serverAvailable = available; }
    public static void clientDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static <T> void sendToPlayer(ServerPlayer player, LegacyPacketTypes.Type<T> type, T message) {
        if (!accepted(player) || !ServerPlayNetworking.canSend(player, CHANNEL)) return;
        FriendlyByteBuf buffer = PacketByteBufs.create();
        LegacyEnvelope.encode(type, message).write(buffer);
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }

    public static void receiveClient(FriendlyByteBuf buffer) {
        LegacyEnvelope envelope = LegacyEnvelope.read(buffer);
        Object message = envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND);
        ClientListener listener = clientListener;
        if (listener != null) listener.accept(envelope.type(), message);
    }

    public static void sendToServer(FriendlyByteBuf buffer) {
        if (!serverAvailable) return;
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(CHANNEL, buffer);
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        sendToServer(encode(type, message));
    }

    public static <T> FriendlyByteBuf encode(LegacyPacketTypes.Type<T> type, T message) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        LegacyEnvelope.encode(type, message).write(buffer);
        return buffer;
    }

    private static void handleServer(ServerPlayer player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) message;
            if (!CAPABILITIES.accept(player.getUUID(), hello.protocolVersion(), Jammarr.PROTOCOL)) {
                player.connection.disconnect(new TextComponent(
                        "Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL));
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
            player.connection.disconnect(new TextComponent("Jammarr hello is required before play packets"));
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

    public static void serverTick() { ticks++; CAPABILITIES.expire(ticks); }
    public static boolean accepted(ServerPlayer player) {
        return player != null && CAPABILITIES.capable(player.getUUID());
    }
    public static void playerLeft(ServerPlayer player) { CAPABILITIES.remove(player.getUUID()); }
    public static synchronized void shutdown() {
        CAPABILITIES.clear();
        serverListener = null;
        clientListener = null;
        ticks = 0L;
    }
    private LegacyNetwork() {}
}
