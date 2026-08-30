package stonytark.jammarr.network;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionPhase;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.PlayPayloadContext;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.server.JammarrServer;

public final class JammarrNetwork {
    public static final int PROTOCOL = ProtocolLimits.VERSION;
    public static final String VERSION = Integer.toString(PROTOCOL);
    private static volatile boolean serverAvailable;

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }
    public static void serverConnected(Connection connection) {
        serverAvailable = NetworkRegistry.getInstance().isConnected(connection, ConnectionPhase.PLAY, JammarrEnvelope.ID);
    }
    public static void serverDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static void sendToServer(JammarrMessage payload) {
        if (serverAvailable) PacketDistributor.SERVER.noArg().send(new JammarrEnvelope(payload));
    }
    public static void sendToPlayer(ServerPlayer player, JammarrMessage payload) {
        if (JammarrServer.instance().accepted(player)) {
            PacketDistributor.PLAYER.with(player).send(new JammarrEnvelope(payload));
        }
    }

    public static void register(RegisterPayloadHandlerEvent event) {
        IPayloadRegistrar registrar = event.registrar(Jammarr.MODID).versioned(VERSION).optional();
        registrar.play(JammarrEnvelope.ID, JammarrEnvelope::read, handlers -> handlers
                .client(JammarrNetwork::client)
                .server(JammarrNetwork::server));
    }

    private static void client(JammarrEnvelope envelope, PlayPayloadContext context) {
        context.workHandler().execute(() -> ClientPayloadBridge.accept(envelope.message()));
    }

    private static void server(JammarrEnvelope envelope, PlayPayloadContext context) {
        context.player().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).ifPresent(player ->
                context.workHandler().execute(() -> dispatch(player, envelope.message(), context)));
    }

    private static void dispatch(ServerPlayer player, JammarrMessage payload, PlayPayloadContext context) {
        if (payload instanceof JammarrPayloads.ClientHello value) {
            if (!protocolMatches(value.protocolVersion())) {
                context.packetHandler().disconnect(Component.literal(
                        "Jammarr protocol mismatch: server requires version " + PROTOCOL));
            } else JammarrServer.instance().hello(player, value);
        } else if (payload instanceof JammarrPayloads.TimeSyncRequest value) {
            context.replyHandler().send(new JammarrEnvelope(new JammarrPayloads.TimeSyncResponse(
                    value.nonce(), value.clientSentEpochMs(), System.currentTimeMillis())));
        } else if (payload instanceof JammarrPayloads.BrowseRequest value) JammarrServer.instance().browse(player, value);
        else if (payload instanceof JammarrPayloads.QueueRequest value) JammarrServer.instance().queue(player, value);
        else if (payload instanceof JammarrPayloads.ControlRequest value) JammarrServer.instance().control(player, value);
        else if (payload instanceof JammarrPayloads.StationRequest value) JammarrServer.instance().station(player, value);
        else if (payload instanceof JammarrPayloads.ChunkRequest value) JammarrServer.instance().chunks(player, value);
        else if (payload instanceof JammarrPayloads.ChunkAcknowledgement value) JammarrServer.instance().acknowledge(player, value);
        else if (payload instanceof JammarrPayloads.AudioHealth value) JammarrServer.instance().health(player, value);
        else if (payload instanceof JammarrPayloads.ManifestRequest) JammarrServer.instance().sync(player);
    }

    private JammarrNetwork() {}
}
