package stonytark.jammarr.network;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.util.UUID;
import java.util.function.Supplier;

/** Forge 1.16.5 adapter for the canonical protocol-6 codecs. */
public final class LegacyNetwork {
    public interface ServerListener {
        void accept(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message);
    }
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    private static final String VERSION = Integer.toString(Jammarr.PROTOCOL);
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Jammarr.MOD_ID, "play"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));
    private static final ClientCapabilityRegistry<UUID> CAPABILITIES =
            new ClientCapabilityRegistry<UUID>(ProtocolLimits.serverHelloTimeoutTicks());
    private static volatile ServerListener serverListener;
    private static volatile ClientListener clientListener;
    private static volatile boolean serverAvailable;
    private static long ticks;
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(0, LegacyEnvelope.class, LegacyEnvelope::write, LegacyEnvelope::read,
                LegacyNetwork::receive);
        registered = true;
    }

    private static void receive(LegacyEnvelope envelope, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        NetworkDirection direction = context.getDirection();
        context.enqueueWork(() -> {
            try {
                if (direction == NetworkDirection.PLAY_TO_SERVER) {
                    ServerPlayerEntity sender = context.getSender();
                    if (sender != null) handleServer(sender, envelope.type(),
                            envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND));
                } else if (direction == NetworkDirection.PLAY_TO_CLIENT) {
                    ClientListener listener = clientListener;
                    if (listener != null) listener.accept(envelope.type(),
                            envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND));
                }
            } catch (ProtocolException malformed) {
                ServerPlayerEntity sender = context.getSender();
                if (sender != null) sender.connection.disconnect(new StringTextComponent("Malformed Jammarr packet"));
                else Jammarr.LOGGER.warn("Ignoring malformed Jammarr client packet", malformed);
            }
        });
        context.setPacketHandled(true);
    }

    public static void setServerListener(ServerListener listener) { serverListener = listener; }
    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static void clientConnected(NetworkManager manager) {
        serverAvailable = manager != null && CHANNEL.isRemotePresent(manager);
    }
    public static void clientDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static void playerJoined(ServerPlayerEntity player) {
        boolean remotePresent = player != null && player.connection != null
                && CHANNEL.isRemotePresent(player.connection.connection);
        CAPABILITIES.connected(player.getUUID(), ticks, remotePresent);
    }

    public static <T> void sendToPlayer(ServerPlayerEntity player, LegacyPacketTypes.Type<T> type, T message) {
        if (!accepted(player) || player.connection == null
                || !CHANNEL.isRemotePresent(player.connection.connection)) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), LegacyEnvelope.encode(type, message));
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        if (serverAvailable) CHANNEL.sendToServer(LegacyEnvelope.encode(type, message));
    }

    private static void handleServer(ServerPlayerEntity player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) message;
            if (!CAPABILITIES.accept(player.getUUID(), hello.protocolVersion(), Jammarr.PROTOCOL)) {
                player.connection.disconnect(new StringTextComponent(
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
            player.connection.disconnect(new StringTextComponent("Jammarr hello is required before play packets"));
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
    public static boolean accepted(ServerPlayerEntity player) {
        return player != null && CAPABILITIES.capable(player.getUUID());
    }
    public static void playerLeft(ServerPlayerEntity player) {
        if (player != null) CAPABILITIES.remove(player.getUUID());
    }
    public static synchronized void shutdown() {
        CAPABILITIES.clear();
        serverListener = null;
        clientListener = null;
        ticks = 0L;
    }
    private LegacyNetwork() {}
}
