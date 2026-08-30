package stonytark.jammarr.network;

import cpw.mods.fml.common.IPlayerTracker;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.IConnectionHandler;
import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.Player;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.NetLoginHandler;
import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet1Login;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.server.MinecraftServer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.network.ClientCapabilityRegistry;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolCapabilities;
import stonytark.jammarr.core.protocol.ProtocolException;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Forge 1.6.4 Packet250 adapter for the canonical protocol-6 codecs. */
public final class LegacyNetwork implements IPacketHandler, IConnectionHandler, IPlayerTracker, ITickHandler {
    public interface ServerListener {
        void accept(EntityPlayerMP player, LegacyPacketTypes.Type<?> type, Object message);
    }

    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    private static final LegacyNetwork INSTANCE = new LegacyNetwork();
    private static final Queue<ServerIncoming> SERVER_INBOX = new ConcurrentLinkedQueue<ServerIncoming>();
    private static final Queue<ClientIncoming> CLIENT_INBOX = new ConcurrentLinkedQueue<ClientIncoming>();

    private final ClientCapabilityRegistry<UUID> capabilities = new ClientCapabilityRegistry<UUID>(
            ProtocolLimits.serverHelloTimeoutMs());
    private volatile ServerListener serverListener;
    private volatile ClientListener clientListener;
    private volatile boolean serverAvailable;
    private volatile INetworkManager clientManager;
    private boolean registered;

    public static synchronized void register() {
        if (INSTANCE.registered) return;
        NetworkRegistry.instance().registerChannel(INSTANCE, Jammarr.MOD_ID);
        NetworkRegistry.instance().registerConnectionHandler(INSTANCE);
        GameRegistry.registerPlayerTracker(INSTANCE);
        TickRegistry.registerTickHandler(INSTANCE, Side.SERVER);
        INSTANCE.registered = true;
    }

    public static void setServerListener(ServerListener listener) { INSTANCE.serverListener = listener; }
    public static void setClientListener(ClientListener listener) { INSTANCE.clientListener = listener; }

    public static <T> void sendToPlayer(EntityPlayerMP player, LegacyPacketTypes.Type<T> type, T message) {
        if (player == null || !INSTANCE.capabilities.capable(playerId(player))) return;
        player.playerNetServerHandler.sendPacketToPlayer(packet(LegacyClientboundEnvelope.of(type, message)));
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        INetworkManager manager = INSTANCE.clientManager;
        if (!INSTANCE.serverAvailable || manager == null) return;
        manager.addToSendQueue(packet(LegacyServerboundEnvelope.of(type, message)));
    }

    public static boolean serverAvailable() { return INSTANCE.serverAvailable; }

    public static void clientTick(boolean channelActive) {
        INSTANCE.serverAvailable = channelActive;
        ClientIncoming incoming;
        ClientListener listener = INSTANCE.clientListener;
        while ((incoming = CLIENT_INBOX.poll()) != null) {
            INSTANCE.serverAvailable = true;
            if (listener != null) listener.accept(incoming.type, incoming.message);
        }
    }

    public static void disconnectClient(String reason) {
        INetworkManager manager = INSTANCE.clientManager;
        if (manager != null) manager.networkShutdown(reason, new Object[0]);
    }

    public static synchronized void shutdown() {
        SERVER_INBOX.clear();
        CLIENT_INBOX.clear();
        INSTANCE.capabilities.clear();
        INSTANCE.serverListener = null;
        INSTANCE.clientListener = null;
        INSTANCE.serverAvailable = false;
        INSTANCE.clientManager = null;
    }

    @Override
    public void onPacketData(INetworkManager manager, Packet250CustomPayload packet, Player networkPlayer) {
        if (!Jammarr.MOD_ID.equals(packet.channel)) return;
        try {
            LegacyEnvelope envelope = LegacyEnvelope.fromPacket(packet.data);
            if (networkPlayer instanceof EntityPlayerMP) {
                EntityPlayerMP player = (EntityPlayerMP) networkPlayer;
                SERVER_INBOX.add(new ServerIncoming(player, envelope.type(),
                        envelope.decode(LegacyPacketTypes.Direction.SERVERBOUND)));
            } else {
                CLIENT_INBOX.add(new ClientIncoming(envelope.type(),
                        envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND)));
            }
        } catch (ProtocolException malformed) {
            if (networkPlayer instanceof EntityPlayerMP) {
                ((EntityPlayerMP) networkPlayer).playerNetServerHandler.kickPlayerFromServer(
                        "Malformed Jammarr packet: " + malformed.getMessage());
            } else {
                manager.networkShutdown("Malformed Jammarr packet: " + malformed.getMessage(), new Object[0]);
            }
        }
    }

    @Override public void onPlayerLogin(EntityPlayer player) {
        if (player instanceof EntityPlayerMP) {
            capabilities.connected(playerId(player), System.currentTimeMillis(), true);
        }
    }

    @Override public void onPlayerLogout(EntityPlayer player) {
        capabilities.remove(playerId(player));
        Jammarr.playerLeft(player);
    }

    @Override public void onPlayerChangedDimension(EntityPlayer player) {}
    @Override public void onPlayerRespawn(EntityPlayer player) {}

    @Override public void tickStart(EnumSet<TickType> type, Object... tickData) {}

    @Override public void tickEnd(EnumSet<TickType> type, Object... tickData) {
        if (!type.contains(TickType.SERVER)) return;
        ServerIncoming incoming;
        while ((incoming = SERVER_INBOX.poll()) != null) handleServer(incoming);
        capabilities.expire(System.currentTimeMillis());
        Jammarr.serverTick();
    }

    @Override public EnumSet<TickType> ticks() { return EnumSet.of(TickType.SERVER); }
    @Override public String getLabel() { return "Jammarr server"; }

    @Override public void playerLoggedIn(Player player, NetHandler handler, INetworkManager manager) {}
    @Override public String connectionReceived(NetLoginHandler handler, INetworkManager manager) { return null; }

    @Override public void connectionOpened(NetHandler handler, String server, int port, INetworkManager manager) {
        clientManager = manager;
        serverAvailable = false;
        CLIENT_INBOX.clear();
    }

    @Override public void connectionOpened(NetHandler handler, MinecraftServer server, INetworkManager manager) {
        clientManager = manager;
        serverAvailable = false;
        CLIENT_INBOX.clear();
    }

    @Override public void connectionClosed(INetworkManager manager) {
        if (manager == clientManager) {
            if (Boolean.getBoolean("jammarr.acceptance.enabled")) {
                Jammarr.LOGGER.info("Client disconnected with reason: Disconnected");
            }
            clientManager = null;
            serverAvailable = false;
            CLIENT_INBOX.clear();
        }
    }

    @Override public void clientLoggedIn(NetHandler handler, INetworkManager manager, Packet1Login login) {
        clientManager = manager;
        serverAvailable = NetworkRegistry.instance().isChannelActive(Jammarr.MOD_ID, (Player) handler.getPlayer());
    }

    private void handleServer(ServerIncoming incoming) {
        EntityPlayerMP player = incoming.player;
        UUID id = playerId(player);
        if (incoming.type == LegacyPacketTypes.CLIENT_HELLO) {
            ControlPackets.ClientHello hello = (ControlPackets.ClientHello) incoming.message;
            if (hello.protocolVersion() != Jammarr.PROTOCOL) {
                capabilities.accept(id, hello.protocolVersion(), Jammarr.PROTOCOL);
                String reason = "Jammarr protocol mismatch: server requires protocol " + Jammarr.PROTOCOL;
                Jammarr.LOGGER.warn("Rejecting {}: {}", player.getEntityName(), reason);
                player.playerNetServerHandler.kickPlayerFromServer(reason);
                return;
            }
            if (!capabilities.accept(id, hello.protocolVersion(), Jammarr.PROTOCOL)) return;
            ProtocolCapabilities.Negotiated negotiated = ProtocolCapabilities.negotiate(
                    hello.features(), hello.audioChunkBytes(), hello.chunksPerRequest());
            sendToPlayer(player, LegacyPacketTypes.SERVER_HELLO,
                    new ControlPackets.ServerHello(Jammarr.PROTOCOL, System.currentTimeMillis(),
                            negotiated.features(), negotiated.audioChunkBytes(), negotiated.chunksPerRequest()));
            ServerListener listener = serverListener;
            if (listener != null) listener.accept(player, incoming.type, incoming.message);
            return;
        }
        if (!capabilities.capable(id)) {
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

    public static boolean accepted(EntityPlayerMP player) {
        return player != null && INSTANCE.capabilities.capable(playerId(player));
    }

    public static UUID playerId(EntityPlayer player) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getEntityName()).getBytes(StandardCharsets.UTF_8));
    }

    private static Packet250CustomPayload packet(LegacyEnvelope envelope) {
        return new Packet250CustomPayload(Jammarr.MOD_ID, envelope.toPacket());
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
