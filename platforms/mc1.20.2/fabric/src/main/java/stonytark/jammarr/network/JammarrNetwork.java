package stonytark.jammarr.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.server.JammarrServer;

import java.util.function.Consumer;

public final class JammarrNetwork {
    public static final int PROTOCOL = ProtocolLimits.VERSION;
    public static final String VERSION = Integer.toString(PROTOCOL);
    private static volatile Consumer<JammarrMessage> clientSender;
    private static volatile MinecraftServer server;

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }
    public static void installClientSender(Consumer<JammarrMessage> sender) { clientSender = sender; }
    public static void activeServer(MinecraftServer value) { server = value; }

    public static void sendToServer(JammarrMessage payload) {
        Consumer<JammarrMessage> sender = clientSender;
        if (sender == null) throw new IllegalStateException("Jammarr client networking is not initialized");
        sender.accept(payload);
    }

    public static void sendToPlayer(ServerPlayer player, JammarrMessage payload) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        JammarrPayloads.write(payload, buffer);
        ServerPlayNetworking.send(player, JammarrPayloads.idOf(payload), buffer);
    }

    public static void sendToAllPlayers(JammarrMessage payload) {
        MinecraftServer current = server;
        if (current != null) for (ServerPlayer player : current.getPlayerList().getPlayers()) sendToPlayer(player, payload);
    }

    public static void register() {
        receive(JammarrPayloads.ClientHello.ID, JammarrPayloads.ClientHello::read, (player, payload) -> {
            if (!protocolMatches(payload.protocolVersion())) {
                player.connection.disconnect(Component.literal("Jammarr protocol mismatch: server requires version " + PROTOCOL));
            } else JammarrServer.instance().hello(player);
        });
        receive(JammarrPayloads.TimeSyncRequest.ID, JammarrPayloads.TimeSyncRequest::read, (player, payload) -> {
            if (JammarrServer.instance().accepted(player)) sendToPlayer(player,
                    new JammarrPayloads.TimeSyncResponse(payload.nonce(), payload.clientSentEpochMs(), System.currentTimeMillis()));
        });
        receive(JammarrPayloads.BrowseRequest.ID, JammarrPayloads.BrowseRequest::read,
                (player, payload) -> JammarrServer.instance().browse(player, payload));
        receive(JammarrPayloads.QueueRequest.ID, JammarrPayloads.QueueRequest::read,
                (player, payload) -> JammarrServer.instance().queue(player, payload));
        receive(JammarrPayloads.ControlRequest.ID, JammarrPayloads.ControlRequest::read,
                (player, payload) -> JammarrServer.instance().control(player, payload));
        receive(JammarrPayloads.StationRequest.ID, JammarrPayloads.StationRequest::read,
                (player, payload) -> JammarrServer.instance().station(player, payload));
        receive(JammarrPayloads.ChunkRequest.ID, JammarrPayloads.ChunkRequest::read,
                (player, payload) -> JammarrServer.instance().chunks(player, payload));
        receive(JammarrPayloads.ChunkAcknowledgement.ID, JammarrPayloads.ChunkAcknowledgement::read,
                (player, payload) -> JammarrServer.instance().acknowledge(player, payload));
        receive(JammarrPayloads.AudioHealth.ID, JammarrPayloads.AudioHealth::read,
                (player, payload) -> JammarrServer.instance().health(player, payload));
        receive(JammarrPayloads.ManifestRequest.ID, JammarrPayloads.ManifestRequest::read,
                (player, payload) -> JammarrServer.instance().sync(player));
    }

    public static FriendlyByteBuf encode(JammarrMessage payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        JammarrPayloads.write(payload, buffer);
        return buffer;
    }

    private static <T extends JammarrMessage> void receive(ResourceLocation id, Decoder<T> decoder, ServerHandler<T> action) {
        ServerPlayNetworking.registerGlobalReceiver(id, (server, player, handler, buffer, responseSender) -> {
            final T payload;
            try {
                payload = decoder.read(buffer);
                if (buffer.readableBytes() != 0) throw new IllegalArgumentException("Trailing bytes in Jammarr packet");
            } catch (RuntimeException malformed) {
                server.execute(() -> player.connection.disconnect(Component.literal("Malformed Jammarr packet")));
                return;
            }
            server.execute(() -> action.handle(player, payload));
        });
    }

    @FunctionalInterface public interface Decoder<T> { T read(FriendlyByteBuf buffer); }
    @FunctionalInterface private interface ServerHandler<T> { void handle(ServerPlayer player, T payload); }
    private JammarrNetwork() {}
}
