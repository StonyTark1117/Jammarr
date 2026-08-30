package stonytark.jammarr.network;

import net.minecraft.server.entity.living.player.ServerPlayerEntity;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.networking.api.ChannelIdentifiers;
import net.ornithemc.osl.networking.api.ChannelRegistry;
import net.ornithemc.osl.networking.api.PacketBuffer;
import net.ornithemc.osl.networking.api.PacketBuffers;
import net.ornithemc.osl.networking.api.client.ClientPlayNetworking;
import net.ornithemc.osl.networking.api.server.ServerConnectionEvents;
import net.ornithemc.osl.networking.api.server.ServerPlayNetworking;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.util.UUID;

/** Ornithe 1.6.4 adapter for the canonical protocol-6 codecs. */
public final class LegacyNetwork {
    public interface ServerListener {
        void accept(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message);
    }
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    public static final NamespacedIdentifier CHANNEL = ChannelIdentifiers.from("jammarr", "main");
    private static final ClientCapabilityRegistry<UUID> CAPABILITIES =
            new ClientCapabilityRegistry<UUID>(ProtocolLimits.serverHelloTimeoutMs());
    private static volatile ServerListener serverListener;
    private static volatile ClientListener clientListener;
    private static volatile boolean serverAvailable;
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        ChannelRegistry.register(CHANNEL);
        ServerPlayNetworking.registerListener(CHANNEL, (context, buffer) -> {
            context.ensureOnMainThread();
            final LegacyEnvelope envelope;
            final Object message;
            try {
                envelope = LegacyEnvelope.read(buffer);
                message = envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND);
            } catch (ProtocolException malformed) {
                context.networkHandler().disconnect("Malformed Jammarr packet");
                return;
            }
            handleServer(context.player(), envelope.type(), message);
        });
        ServerConnectionEvents.PLAY_READY.register(context ->
                CAPABILITIES.connected(context.player().getUuid(), System.currentTimeMillis(),
                        ServerPlayNetworking.isPlayReady(context.player(), CHANNEL)));
        registered = true;
    }

    public static void setServerListener(ServerListener listener) { serverListener = listener; }
    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static void clientConnected(boolean available) { serverAvailable = available; }
    public static void clientDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static <T> void sendToPlayer(ServerPlayerEntity player, LegacyPacketTypes.Type<T> type, T message) {
        if (!accepted(player) || !ServerPlayNetworking.isPlayReady(player, CHANNEL)) return;
        PacketBuffer buffer = PacketBuffers.make();
        LegacyEnvelope.encode(type, message).write(buffer);
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }

    public static void receiveClient(PacketBuffer buffer) {
        LegacyEnvelope envelope = LegacyEnvelope.read(buffer);
        Object message = envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND);
        ClientListener listener = clientListener;
        if (listener != null) listener.accept(envelope.type(), message);
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        if (!serverAvailable) return;
        PacketBuffer buffer = PacketBuffers.make();
        LegacyEnvelope.encode(type, message).write(buffer);
        ClientPlayNetworking.send(CHANNEL, buffer);
    }

    private static void handleServer(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) message;
            if (!CAPABILITIES.accept(player.getUuid(), hello.protocolVersion(), Jammarr.PROTOCOL)) {
                String reason = "Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL;
                Jammarr.LOGGER.warn("{}", reason);
                player.networkHandler.disconnect(reason);
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
