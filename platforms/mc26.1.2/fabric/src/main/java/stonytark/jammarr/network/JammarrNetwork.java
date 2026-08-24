package stonytark.jammarr.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.server.JammarrServer;

import java.util.function.Consumer;

public final class JammarrNetwork {
    public static final int PROTOCOL = ProtocolLimits.VERSION;
    public static final String VERSION = Integer.toString(PROTOCOL);
    private static volatile Consumer<CustomPacketPayload> clientSender;
    private static volatile MinecraftServer server;

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }
    public static void installClientSender(Consumer<CustomPacketPayload> sender) { clientSender = sender; }
    public static void activeServer(MinecraftServer value) { server = value; }

    public static void sendToServer(JammarrMessage payload) {
        Consumer<CustomPacketPayload> sender = clientSender;
        if (sender == null) throw new IllegalStateException("Jammarr client networking is not initialized");
        sender.accept((CustomPacketPayload)payload);
    }
    public static void sendToPlayer(ServerPlayer player, JammarrMessage payload) {
        ServerPlayNetworking.send(player, (CustomPacketPayload)payload);
    }
    public static void sendToAllPlayers(JammarrMessage payload) {
        MinecraftServer current = server;
        if (current != null) for (ServerPlayer player : current.getPlayerList().getPlayers()) sendToPlayer(player, payload);
    }

    public static void register() {
        registerS2C(JammarrPayloads.OpenScreen.TYPE, JammarrPayloads.OpenScreen.CODEC);
        registerS2C(JammarrPayloads.ServerHello.TYPE, JammarrPayloads.ServerHello.CODEC);
        registerS2C(JammarrPayloads.TimeSyncResponse.TYPE, JammarrPayloads.TimeSyncResponse.CODEC);
        registerS2C(JammarrPayloads.BrowseResults.TYPE, JammarrPayloads.BrowseResults.CODEC);
        registerS2C(JammarrPayloads.AudioManifest.TYPE, JammarrPayloads.AudioManifest.CODEC);
        registerS2C(JammarrPayloads.AudioChunk.TYPE, JammarrPayloads.AudioChunk.CODEC);
        registerS2C(JammarrPayloads.PlaybackState.TYPE, JammarrPayloads.PlaybackState.CODEC);
        registerS2C(JammarrPayloads.StationState.TYPE, JammarrPayloads.StationState.CODEC);
        registerS2C(JammarrPayloads.AdventurePreview.TYPE, JammarrPayloads.AdventurePreview.CODEC);
        registerS2C(JammarrPayloads.ErrorMessage.TYPE, JammarrPayloads.ErrorMessage.CODEC);
        registerC2S(JammarrPayloads.ClientHello.TYPE, JammarrPayloads.ClientHello.CODEC);
        registerC2S(JammarrPayloads.TimeSyncRequest.TYPE, JammarrPayloads.TimeSyncRequest.CODEC);
        registerC2S(JammarrPayloads.BrowseRequest.TYPE, JammarrPayloads.BrowseRequest.CODEC);
        registerC2S(JammarrPayloads.QueueRequest.TYPE, JammarrPayloads.QueueRequest.CODEC);
        registerC2S(JammarrPayloads.ControlRequest.TYPE, JammarrPayloads.ControlRequest.CODEC);
        registerC2S(JammarrPayloads.StationRequest.TYPE, JammarrPayloads.StationRequest.CODEC);
        registerC2S(JammarrPayloads.ChunkRequest.TYPE, JammarrPayloads.ChunkRequest.CODEC);
        registerC2S(JammarrPayloads.ChunkAcknowledgement.TYPE, JammarrPayloads.ChunkAcknowledgement.CODEC);
        registerC2S(JammarrPayloads.AudioHealth.TYPE, JammarrPayloads.AudioHealth.CODEC);
        registerC2S(JammarrPayloads.ManifestRequest.TYPE, JammarrPayloads.ManifestRequest.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.ClientHello.TYPE, (payload, context) -> {
            if (!protocolMatches(payload.protocolVersion())) {
                context.player().connection.disconnect(Component.literal(
                        "Jammarr protocol mismatch: server requires version " + PROTOCOL));
            } else JammarrServer.instance().hello(context.player());
        });
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.TimeSyncRequest.TYPE, (p, c) -> {
            if (JammarrServer.instance().accepted(c.player())) {
                c.responseSender().sendPacket(new JammarrPayloads.TimeSyncResponse(p.nonce(), p.clientSentEpochMs(), System.currentTimeMillis()));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.BrowseRequest.TYPE, (p, c) -> JammarrServer.instance().browse(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.QueueRequest.TYPE, (p, c) -> JammarrServer.instance().queue(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.ControlRequest.TYPE, (p, c) -> JammarrServer.instance().control(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.StationRequest.TYPE, (p, c) -> JammarrServer.instance().station(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.ChunkRequest.TYPE, (p, c) -> JammarrServer.instance().chunks(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.ChunkAcknowledgement.TYPE, (p, c) -> JammarrServer.instance().acknowledge(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.AudioHealth.TYPE, (p, c) -> JammarrServer.instance().health(c.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(JammarrPayloads.ManifestRequest.TYPE, (p, c) -> JammarrServer.instance().sync(c.player()));
    }

    private static <T extends CustomPacketPayload & JammarrMessage> void registerS2C(CustomPacketPayload.Type<T> type,
                                                                    net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }
    private static <T extends CustomPacketPayload & JammarrMessage> void registerC2S(CustomPacketPayload.Type<T> type,
                                                                    net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
    }
    private JammarrNetwork() {}
}
