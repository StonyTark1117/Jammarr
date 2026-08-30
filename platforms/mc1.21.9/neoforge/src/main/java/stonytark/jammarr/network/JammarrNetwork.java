package stonytark.jammarr.network;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.server.JammarrServer;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.JammarrMessage;

public final class JammarrNetwork {
    /** Bumped for source-aware queues, stations, and Sonic Adventure payloads. */
    public static final int PROTOCOL = ProtocolLimits.VERSION;
    public static final String VERSION = Integer.toString(PROTOCOL);
    private static volatile boolean serverAvailable;

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }
    public static void serverConnected(Connection connection) {
        serverAvailable = NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, JammarrPayloads.ClientHello.TYPE.id());
    }
    public static void serverDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static void sendToServer(JammarrMessage payload) {
        if (serverAvailable) ClientPacketDistributor.sendToServer((CustomPacketPayload)payload);
    }
    public static void sendToPlayer(ServerPlayer player, JammarrMessage payload) {
        if (JammarrServer.instance().accepted(player)) PacketDistributor.sendToPlayer(player, (CustomPacketPayload)payload);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION).optional();
        registrar.playToClient(JammarrPayloads.OpenScreen.TYPE, JammarrPayloads.OpenScreen.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.ServerHello.TYPE, JammarrPayloads.ServerHello.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.TimeSyncResponse.TYPE, JammarrPayloads.TimeSyncResponse.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.BrowseResults.TYPE, JammarrPayloads.BrowseResults.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.AudioManifest.TYPE, JammarrPayloads.AudioManifest.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.AudioChunk.TYPE, JammarrPayloads.AudioChunk.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.PlaybackState.TYPE, JammarrPayloads.PlaybackState.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.StationState.TYPE, JammarrPayloads.StationState.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.AdventurePreview.TYPE, JammarrPayloads.AdventurePreview.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.ErrorMessage.TYPE, JammarrPayloads.ErrorMessage.CODEC, JammarrNetwork::client);
        registrar.playToServer(JammarrPayloads.ClientHello.TYPE, JammarrPayloads.ClientHello.CODEC, (payload, context) -> {
            if (!protocolMatches(payload.protocolVersion())) {
                ServerPlayer player = (ServerPlayer)context.player();
                String reason = "Jammarr protocol mismatch: server requires version " + PROTOCOL;
                Jammarr.LOGGER.warn("Disconnecting {}: {}", player.getGameProfile().name(), reason);
                player.connection.send(new ClientboundDisconnectPacket(Component.literal(reason)));
            } else {
                context.enqueueWork(() -> JammarrServer.instance().hello((ServerPlayer)context.player(), payload));
            }
        });
        registrar.playToServer(JammarrPayloads.TimeSyncRequest.TYPE, JammarrPayloads.TimeSyncRequest.CODEC,
                (p, c) -> c.reply(new JammarrPayloads.TimeSyncResponse(p.nonce(), p.clientSentEpochMs(), System.currentTimeMillis())));
        registrar.playToServer(JammarrPayloads.BrowseRequest.TYPE, JammarrPayloads.BrowseRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().browse((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.QueueRequest.TYPE, JammarrPayloads.QueueRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().queue((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.ControlRequest.TYPE, JammarrPayloads.ControlRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().control((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.StationRequest.TYPE, JammarrPayloads.StationRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().station((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.ChunkRequest.TYPE, JammarrPayloads.ChunkRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().chunks((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.ChunkAcknowledgement.TYPE, JammarrPayloads.ChunkAcknowledgement.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().acknowledge((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.AudioHealth.TYPE, JammarrPayloads.AudioHealth.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().health((ServerPlayer)c.player(), p)));
        registrar.playToServer(JammarrPayloads.ManifestRequest.TYPE, JammarrPayloads.ManifestRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> JammarrServer.instance().sync((ServerPlayer)c.player())));
    }

    private static void client(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload,
                               net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.accept((JammarrMessage)payload));
    }

    private JammarrNetwork() {}
}
