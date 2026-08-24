package stonytark.jammarr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.server.JammarrServer;

import java.util.function.BiConsumer;
import java.util.function.Function;

public final class JammarrNetwork {
    public static final int PROTOCOL = ProtocolLimits.VERSION;
    public static final String VERSION = Integer.toString(PROTOCOL);
    private static SimpleChannel channel;

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }

    public static void register() {
        channel = ChannelBuilder
                .named(new ResourceLocation(Jammarr.MODID, "main"))
                .networkProtocolVersion(PROTOCOL)
                .simpleChannel();
        int id = 0;
        client(id++, JammarrPayloads.OpenScreen.class, (value, buffer) -> {}, buffer -> new JammarrPayloads.OpenScreen());
        client(id++, JammarrPayloads.ServerHello.class, JammarrPayloads.ServerHello::write, JammarrPayloads.ServerHello::read);
        client(id++, JammarrPayloads.TimeSyncResponse.class, JammarrPayloads.TimeSyncResponse::write, JammarrPayloads.TimeSyncResponse::read);
        client(id++, JammarrPayloads.BrowseResults.class, JammarrPayloads.BrowseResults::write, JammarrPayloads.BrowseResults::read);
        client(id++, JammarrPayloads.AudioManifest.class, JammarrPayloads.AudioManifest::write, JammarrPayloads.AudioManifest::read);
        client(id++, JammarrPayloads.AudioChunk.class, JammarrPayloads.AudioChunk::write, JammarrPayloads.AudioChunk::read);
        client(id++, JammarrPayloads.PlaybackState.class, JammarrPayloads.PlaybackState::write, JammarrPayloads.PlaybackState::read);
        client(id++, JammarrPayloads.StationState.class, JammarrPayloads.StationState::write, JammarrPayloads.StationState::read);
        client(id++, JammarrPayloads.AdventurePreview.class, JammarrPayloads.AdventurePreview::write, JammarrPayloads.AdventurePreview::read);
        client(id++, JammarrPayloads.ErrorMessage.class, JammarrPayloads.ErrorMessage::write, JammarrPayloads.ErrorMessage::read);

        server(id++, JammarrPayloads.ClientHello.class, JammarrPayloads.ClientHello::write, JammarrPayloads.ClientHello::read,
                (player, payload) -> {
                    if (!protocolMatches(payload.protocolVersion())) {
                        String reason = "Jammarr protocol mismatch: server requires version " + PROTOCOL;
                        Jammarr.LOGGER.warn("Disconnecting {}: {}", player.getGameProfile().getName(), reason);
                        player.connection.disconnect(Component.literal(reason));
                    } else JammarrServer.instance().hello(player);
                });
        server(id++, JammarrPayloads.TimeSyncRequest.class, JammarrPayloads.TimeSyncRequest::write, JammarrPayloads.TimeSyncRequest::read,
                (player, payload) -> sendToPlayer(player, new JammarrPayloads.TimeSyncResponse(
                        payload.nonce(), payload.clientSentEpochMs(), System.currentTimeMillis())));
        server(id++, JammarrPayloads.BrowseRequest.class, JammarrPayloads.BrowseRequest::write, JammarrPayloads.BrowseRequest::read,
                (player, payload) -> JammarrServer.instance().browse(player, payload));
        server(id++, JammarrPayloads.QueueRequest.class, JammarrPayloads.QueueRequest::write, JammarrPayloads.QueueRequest::read,
                (player, payload) -> JammarrServer.instance().queue(player, payload));
        server(id++, JammarrPayloads.ControlRequest.class, JammarrPayloads.ControlRequest::write, JammarrPayloads.ControlRequest::read,
                (player, payload) -> JammarrServer.instance().control(player, payload));
        server(id++, JammarrPayloads.StationRequest.class, JammarrPayloads.StationRequest::write, JammarrPayloads.StationRequest::read,
                (player, payload) -> JammarrServer.instance().station(player, payload));
        server(id++, JammarrPayloads.ChunkRequest.class, JammarrPayloads.ChunkRequest::write, JammarrPayloads.ChunkRequest::read,
                (player, payload) -> JammarrServer.instance().chunks(player, payload));
        server(id++, JammarrPayloads.ChunkAcknowledgement.class, JammarrPayloads.ChunkAcknowledgement::write, JammarrPayloads.ChunkAcknowledgement::read,
                (player, payload) -> JammarrServer.instance().acknowledge(player, payload));
        server(id++, JammarrPayloads.AudioHealth.class, JammarrPayloads.AudioHealth::write, JammarrPayloads.AudioHealth::read,
                (player, payload) -> JammarrServer.instance().health(player, payload));
        server(id, JammarrPayloads.ManifestRequest.class, JammarrPayloads.ManifestRequest::write, JammarrPayloads.ManifestRequest::read,
                (player, payload) -> JammarrServer.instance().sync(player));
    }

    public static void sendToServer(JammarrMessage payload) {
        required().send(payload, PacketDistributor.SERVER.noArg());
    }
    public static void sendToPlayer(ServerPlayer player, JammarrMessage payload) {
        required().send(payload, PacketDistributor.PLAYER.with(player));
    }
    public static void sendToAllPlayers(JammarrMessage payload) {
        required().send(payload, PacketDistributor.ALL.noArg());
    }

    private static <T extends JammarrMessage> void client(int id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder) {
        required().messageBuilder(type, id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((payload, context) -> ClientPayloadBridge.accept(payload))
                .add();
    }

    private static <T extends JammarrMessage> void server(int id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            BiConsumer<ServerPlayer, T> action) {
        required().messageBuilder(type, id, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((payload, context) -> withSender(context, player -> action.accept(player, payload)))
                .add();
    }

    private static void withSender(CustomPayloadEvent.Context context, java.util.function.Consumer<ServerPlayer> action) {
        ServerPlayer sender = context.getSender();
        if (sender != null) action.accept(sender);
    }

    private static SimpleChannel required() {
        if (channel == null) throw new IllegalStateException("Jammarr networking is not initialized");
        return channel;
    }

    private JammarrNetwork() {}
}
