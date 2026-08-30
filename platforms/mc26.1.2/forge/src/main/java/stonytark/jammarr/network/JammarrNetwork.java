package stonytark.jammarr.network;

import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.payload.PayloadConnection;
import net.minecraftforge.network.payload.PayloadFlow;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.server.JammarrServer;

import java.util.function.BiConsumer;

public final class JammarrNetwork {
    public static final int PROTOCOL = ProtocolLimits.VERSION;
    public static final String VERSION = Integer.toString(PROTOCOL);
    private static Channel<CustomPacketPayload> channel;
    private static volatile boolean serverAvailable;

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }
    public static void serverConnected(Connection connection) { serverAvailable = required().isRemotePresent(connection); }
    public static void serverDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static void register() {
        if (channel != null) return;
        PayloadConnection<CustomPacketPayload> connection = ChannelBuilder
                .named(Identifier.fromNamespaceAndPath(Jammarr.MODID, "main"))
                .networkProtocolVersion(PROTOCOL).optional().payloadChannel();
        PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> clientbound = connection.play().flow(PacketFlow.CLIENTBOUND);
        client(clientbound, JammarrPayloads.OpenScreen.TYPE, JammarrPayloads.OpenScreen.CODEC);
        client(clientbound, JammarrPayloads.ServerHello.TYPE, JammarrPayloads.ServerHello.CODEC);
        client(clientbound, JammarrPayloads.TimeSyncResponse.TYPE, JammarrPayloads.TimeSyncResponse.CODEC);
        client(clientbound, JammarrPayloads.BrowseResults.TYPE, JammarrPayloads.BrowseResults.CODEC);
        client(clientbound, JammarrPayloads.AudioManifest.TYPE, JammarrPayloads.AudioManifest.CODEC);
        client(clientbound, JammarrPayloads.AudioChunk.TYPE, JammarrPayloads.AudioChunk.CODEC);
        client(clientbound, JammarrPayloads.PlaybackState.TYPE, JammarrPayloads.PlaybackState.CODEC);
        client(clientbound, JammarrPayloads.StationState.TYPE, JammarrPayloads.StationState.CODEC);
        client(clientbound, JammarrPayloads.AdventurePreview.TYPE, JammarrPayloads.AdventurePreview.CODEC);
        client(clientbound, JammarrPayloads.ErrorMessage.TYPE, JammarrPayloads.ErrorMessage.CODEC);

        PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> serverbound = connection.play().flow(PacketFlow.SERVERBOUND);
        serverbound.addMain(JammarrPayloads.ClientHello.TYPE, JammarrPayloads.ClientHello.CODEC, (payload, context) -> {
            if (!protocolMatches(payload.protocolVersion())) {
                withSender(context, sender -> {
                    String reason = "Jammarr protocol mismatch: server requires version " + PROTOCOL;
                    Jammarr.LOGGER.warn("Disconnecting {}: {}", sender.getGameProfile().name(), reason);
                    sender.connection.disconnect(Component.literal(reason));
                });
            } else withSender(context, sender -> JammarrServer.instance().hello(sender, payload));
        });
        serverbound.addMain(JammarrPayloads.TimeSyncRequest.TYPE, JammarrPayloads.TimeSyncRequest.CODEC, (payload, context) ->
                withSender(context, sender -> channel.reply(new JammarrPayloads.TimeSyncResponse(
                        payload.nonce(), payload.clientSentEpochMs(), System.currentTimeMillis()), context)));
        serverbound.addMain(JammarrPayloads.BrowseRequest.TYPE, JammarrPayloads.BrowseRequest.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().browse(sender, payload)));
        serverbound.addMain(JammarrPayloads.QueueRequest.TYPE, JammarrPayloads.QueueRequest.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().queue(sender, payload)));
        serverbound.addMain(JammarrPayloads.ControlRequest.TYPE, JammarrPayloads.ControlRequest.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().control(sender, payload)));
        serverbound.addMain(JammarrPayloads.StationRequest.TYPE, JammarrPayloads.StationRequest.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().station(sender, payload)));
        serverbound.addMain(JammarrPayloads.ChunkRequest.TYPE, JammarrPayloads.ChunkRequest.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().chunks(sender, payload)));
        serverbound.addMain(JammarrPayloads.ChunkAcknowledgement.TYPE, JammarrPayloads.ChunkAcknowledgement.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().acknowledge(sender, payload)));
        serverbound.addMain(JammarrPayloads.AudioHealth.TYPE, JammarrPayloads.AudioHealth.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().health(sender, payload)));
        serverbound.addMain(JammarrPayloads.ManifestRequest.TYPE, JammarrPayloads.ManifestRequest.CODEC,
                (payload, context) -> withSender(context, sender -> JammarrServer.instance().sync(sender)));
        channel = clientbound.build();
    }

    public static void sendToServer(JammarrMessage payload) {
        if (serverAvailable) required().send((CustomPacketPayload)payload, PacketDistributor.SERVER.noArg());
    }
    public static void sendToPlayer(ServerPlayer player, JammarrMessage payload) {
        if (JammarrServer.instance().accepted(player)) required().send((CustomPacketPayload)payload, PacketDistributor.PLAYER.with(player));
    }

    private static <T extends CustomPacketPayload & JammarrMessage> void client(PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> flow,
                                                               CustomPacketPayload.Type<T> type,
                                                               StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        flow.addMain(type, codec, (payload, context) -> ClientPayloadBridge.accept(payload));
    }

    private static void withSender(CustomPayloadEvent.Context context, java.util.function.Consumer<ServerPlayer> action) {
        ServerPlayer sender = context.getSender();
        if (sender != null) action.accept(sender);
    }

    private static Channel<CustomPacketPayload> required() {
        Channel<CustomPacketPayload> value = channel;
        if (value == null) throw new IllegalStateException("Jammarr networking is not initialized");
        return value;
    }

    private JammarrNetwork() {}
}
