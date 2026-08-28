package stonytark.jammarr.network;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.HelloGate;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Forge 1.7.10 SimpleNetworkWrapper adapter for the canonical protocol-5 codecs. */
public final class LegacyNetwork {
    public interface ServerListener {
        void accept(EntityPlayerMP player, LegacyPacketTypes.Type<?> type, Object message);
    }

    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Jammarr.MOD_ID);
    private static final LegacyNetwork INSTANCE = new LegacyNetwork();
    private static final Queue<ServerIncoming> SERVER_INBOX = new ConcurrentLinkedQueue<ServerIncoming>();
    private static final Queue<ClientIncoming> CLIENT_INBOX = new ConcurrentLinkedQueue<ClientIncoming>();

    private final Map<UUID, EntityPlayerMP> pendingPlayers = new HashMap<UUID, EntityPlayerMP>();
    private final HelloGate<UUID> helloGate = new HelloGate<UUID>(ProtocolLimits.serverHelloTimeoutMs());
    private volatile ServerListener serverListener;
    private volatile ClientListener clientListener;
    private boolean registered;

    public static synchronized void register() {
        if (INSTANCE.registered) return;
        CHANNEL.registerMessage(ServerboundHandler.class, LegacyServerboundEnvelope.class, 0, Side.SERVER);
        CHANNEL.registerMessage(ClientboundHandler.class, LegacyClientboundEnvelope.class, 1, Side.CLIENT);
        FMLCommonHandler.instance().bus().register(INSTANCE);
        INSTANCE.registered = true;
    }

    public static void setServerListener(ServerListener listener) { INSTANCE.serverListener = listener; }
    public static void setClientListener(ClientListener listener) { INSTANCE.clientListener = listener; }

    public static <T> void sendToPlayer(EntityPlayerMP player, LegacyPacketTypes.Type<T> type, T message) {
        CHANNEL.sendTo(LegacyClientboundEnvelope.of(type, message), player);
    }

    public static <T> void sendToAll(LegacyPacketTypes.Type<T> type, T message) {
        CHANNEL.sendToAll(LegacyClientboundEnvelope.of(type, message));
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        CHANNEL.sendToServer(LegacyServerboundEnvelope.of(type, message));
    }

    public static synchronized void shutdown() {
        SERVER_INBOX.clear();
        CLIENT_INBOX.clear();
        INSTANCE.pendingPlayers.clear();
        INSTANCE.helloGate.clear();
        INSTANCE.serverListener = null;
        INSTANCE.clientListener = null;
    }

    @SubscribeEvent
    public void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID playerId = player.getUniqueID();
        helloGate.require(playerId, System.currentTimeMillis());
        pendingPlayers.put(playerId, player);
    }

    @SubscribeEvent
    public void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.player.getUniqueID();
        pendingPlayers.remove(playerId);
        helloGate.remove(playerId);
    }

    @SubscribeEvent
    public void clientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        CLIENT_INBOX.clear();
    }

    @SubscribeEvent
    public void clientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        CLIENT_INBOX.clear();
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ServerIncoming incoming;
        while ((incoming = SERVER_INBOX.poll()) != null) handleServer(incoming);
        for (UUID playerId : helloGate.expire(System.currentTimeMillis())) {
            // Remove all mutable handshake state before kicking. Hybrid Forge
            // servers may synchronously fire PlayerLoggedOutEvent from the kick.
            EntityPlayerMP player = pendingPlayers.remove(playerId);
            if (player != null) player.playerNetServerHandler.kickPlayerFromServer(
                    "Jammarr protocol handshake timed out; install the matching Forge 1.7.10 Jammarr client");
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientIncoming incoming;
        ClientListener listener = clientListener;
        while ((incoming = CLIENT_INBOX.poll()) != null) {
            if (listener != null) listener.accept(incoming.type, incoming.message);
        }
    }

    private void handleServer(ServerIncoming incoming) {
        EntityPlayerMP player = incoming.player;
        if (incoming.type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) incoming.message;
            if (hello.protocolVersion() != Jammarr.PROTOCOL) {
                UUID playerId = player.getUniqueID();
                pendingPlayers.remove(playerId);
                helloGate.remove(playerId);
                player.playerNetServerHandler.kickPlayerFromServer(
                        "Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL);
                return;
            }
            UUID playerId = player.getUniqueID();
            if (!helloGate.accept(playerId) && !helloGate.accepted(playerId)) return;
            pendingPlayers.remove(playerId);
            sendToPlayer(player, LegacyPacketTypes.SERVER_HELLO,
                    new ControlPackets.ServerHello(Jammarr.PROTOCOL, System.currentTimeMillis()));
            ServerListener listener = serverListener;
            if (listener != null) listener.accept(player, incoming.type, incoming.message);
            return;
        }
        if (!helloGate.accepted(player.getUniqueID())) {
            UUID playerId = player.getUniqueID();
            pendingPlayers.remove(playerId);
            helloGate.remove(playerId);
            player.playerNetServerHandler.kickPlayerFromServer("Jammarr protocol hello is required before play packets");
            return;
        }
        if (incoming.type == LegacyPacketTypes.TIME_SYNC_REQUEST) {
            ControlPackets.TimeSyncRequest request = (ControlPackets.TimeSyncRequest) incoming.message;
            sendToPlayer(player, LegacyPacketTypes.TIME_SYNC_RESPONSE,
                    new ControlPackets.TimeSyncResponse(request.nonce(), request.clientSentEpochMs(), System.currentTimeMillis()));
            return;
        }
        ServerListener listener = serverListener;
        if (listener != null) listener.accept(player, incoming.type, incoming.message);
    }

    public static final class ServerboundHandler implements IMessageHandler<LegacyServerboundEnvelope, IMessage> {
        @Override
        public IMessage onMessage(LegacyServerboundEnvelope envelope, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            try {
                SERVER_INBOX.add(new ServerIncoming(player, envelope.type(),
                        envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND)));
            } catch (ProtocolException malformed) {
                player.playerNetServerHandler.kickPlayerFromServer("Malformed Jammarr packet: " + malformed.getMessage());
            }
            return null;
        }
    }

    public static final class ClientboundHandler implements IMessageHandler<LegacyClientboundEnvelope, IMessage> {
        @Override
        public IMessage onMessage(LegacyClientboundEnvelope envelope, MessageContext context) {
            CLIENT_INBOX.add(new ClientIncoming(envelope.type(),
                    envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND)));
            return null;
        }
    }

    private static final class ServerIncoming {
        private final EntityPlayerMP player;
        private final LegacyPacketTypes.Type<?> type;
        private final Object message;
        private ServerIncoming(EntityPlayerMP player, LegacyPacketTypes.Type<?> type, Object message) {
            this.player = player;
            this.type = type;
            this.message = message;
        }
    }

    private static final class ClientIncoming {
        private final LegacyPacketTypes.Type<?> type;
        private final Object message;
        private ClientIncoming(LegacyPacketTypes.Type<?> type, Object message) {
            this.type = type;
            this.message = message;
        }
    }

    private LegacyNetwork() {}
}
