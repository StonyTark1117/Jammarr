package stonytark.jammarr.network;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

/** Native 1.6.4 custom-payload bridge; Legacy Fabric has no networking API on 1.6.x. */
public final class LegacyNetwork {
    public interface ServerListener {
        void accept(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message);
    }
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    public static final String CHANNEL = "jammarr";
    private static final ClientCapabilityRegistry<String> CAPABILITIES =
            new ClientCapabilityRegistry<String>(ProtocolLimits.serverHelloTimeoutMs());
    private static volatile ServerListener serverListener;
    private static volatile ClientListener clientListener;
    private static volatile boolean serverAvailable;

    public static void register() { }
    public static void setServerListener(ServerListener listener) { serverListener = listener; }
    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static void clientConnected() { serverAvailable = true; }
    public static void clientDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static void receiveServer(ServerPlayerEntity player, byte[] bytes) {
        final LegacyEnvelope envelope;
        final Object message;
        try {
            envelope = LegacyEnvelope.read(bytes);
            message = envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND);
        } catch (ProtocolException malformed) {
            player.field_2823.disconnect("Malformed Jammarr packet");
            return;
        }
        handleServer(player, envelope.type(), message);
    }

    public static void receiveClient(byte[] bytes) {
        LegacyEnvelope envelope = LegacyEnvelope.read(bytes);
        Object message = envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND);
        ClientListener listener = clientListener;
        if (listener != null) listener.accept(envelope.type(), message);
    }

    public static <T> void sendToPlayer(ServerPlayerEntity player, LegacyPacketTypes.Type<T> type, T message) {
        if (!accepted(player)) return;
        player.field_2823.sendPacket(new CustomPayloadC2SPacket(CHANNEL, LegacyEnvelope.encode(type, message).write()));
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        if (!serverAvailable) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.method_2960() == null) return;
        client.method_2960().sendPacket(new CustomPayloadC2SPacket(CHANNEL, LegacyEnvelope.encode(type, message).write()));
    }

    private static void handleServer(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message) {
        String key = key(player);
        if (type == LegacyPacketTypes.CLIENT_HELLO) {
            CAPABILITIES.connected(key, System.currentTimeMillis(), true);
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) message;
            if (!CAPABILITIES.accept(key, hello.protocolVersion(), Jammarr.PROTOCOL)) {
                String reason = "Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL;
                Jammarr.LOGGER.warn("{}", reason);
                player.field_2823.disconnect(reason);
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
            player.field_2823.disconnect("Jammarr protocol hello is required before play packets");
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
        return player != null && CAPABILITIES.capable(key(player));
    }
    public static void playerLeft(ServerPlayerEntity player) {
        if (player != null) CAPABILITIES.remove(key(player));
    }
    public static synchronized void shutdown() {
        CAPABILITIES.clear();
        serverListener = null;
        clientListener = null;
    }
    private static String key(ServerPlayerEntity player) { return player.getUsername().toLowerCase(java.util.Locale.ROOT); }
    private LegacyNetwork() { }
}
