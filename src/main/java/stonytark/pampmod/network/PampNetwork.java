package stonytark.pampmod.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import stonytark.pampmod.server.PampServer;

public final class PampNetwork {
    public static final int PROTOCOL = 2;
    public static final String VERSION = Integer.toString(PROTOCOL);

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(PampPayloads.OpenScreen.TYPE, PampPayloads.OpenScreen.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.ServerHello.TYPE, PampPayloads.ServerHello.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.TimeSyncResponse.TYPE, PampPayloads.TimeSyncResponse.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.BrowseResults.TYPE, PampPayloads.BrowseResults.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.AudioManifest.TYPE, PampPayloads.AudioManifest.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.AudioChunk.TYPE, PampPayloads.AudioChunk.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.PlaybackState.TYPE, PampPayloads.PlaybackState.CODEC, PampNetwork::client);
        registrar.playToClient(PampPayloads.ErrorMessage.TYPE, PampPayloads.ErrorMessage.CODEC, PampNetwork::client);
        registrar.playToServer(PampPayloads.ClientHello.TYPE, PampPayloads.ClientHello.CODEC, (payload, context) -> {
            if (payload.protocolVersion() != PROTOCOL) {
                context.disconnect(Component.literal("PAmpMod protocol mismatch: server requires version " + PROTOCOL));
            } else {
                context.enqueueWork(() -> PampServer.instance().hello((ServerPlayer)context.player()));
            }
        });
        registrar.playToServer(PampPayloads.TimeSyncRequest.TYPE, PampPayloads.TimeSyncRequest.CODEC,
                (p, c) -> c.reply(new PampPayloads.TimeSyncResponse(p.nonce(), p.clientSentEpochMs(), System.currentTimeMillis())));
        registrar.playToServer(PampPayloads.BrowseRequest.TYPE, PampPayloads.BrowseRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> PampServer.instance().browse((ServerPlayer)c.player(), p)));
        registrar.playToServer(PampPayloads.QueueRequest.TYPE, PampPayloads.QueueRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> PampServer.instance().queue((ServerPlayer)c.player(), p)));
        registrar.playToServer(PampPayloads.ControlRequest.TYPE, PampPayloads.ControlRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> PampServer.instance().control((ServerPlayer)c.player(), p)));
        registrar.playToServer(PampPayloads.ChunkRequest.TYPE, PampPayloads.ChunkRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> PampServer.instance().chunks((ServerPlayer)c.player(), p)));
        registrar.playToServer(PampPayloads.ChunkAcknowledgement.TYPE, PampPayloads.ChunkAcknowledgement.CODEC,
                (p, c) -> c.enqueueWork(() -> PampServer.instance().acknowledge((ServerPlayer)c.player(), p)));
        registrar.playToServer(PampPayloads.ManifestRequest.TYPE, PampPayloads.ManifestRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> PampServer.instance().sync((ServerPlayer)c.player())));
    }

    private static void client(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload,
                               net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.accept(payload));
    }

    private PampNetwork() {}
}
