package stonytark.jammarr.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import stonytark.jammarr.server.JammarrServer;

public final class JammarrNetwork {
    /** Bumped for the AudioHealth listener telemetry payload. */
    public static final int PROTOCOL = 4;
    public static final String VERSION = Integer.toString(PROTOCOL);

    public static boolean protocolMatches(int offered) { return offered == PROTOCOL; }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(JammarrPayloads.OpenScreen.TYPE, JammarrPayloads.OpenScreen.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.ServerHello.TYPE, JammarrPayloads.ServerHello.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.TimeSyncResponse.TYPE, JammarrPayloads.TimeSyncResponse.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.BrowseResults.TYPE, JammarrPayloads.BrowseResults.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.AudioManifest.TYPE, JammarrPayloads.AudioManifest.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.AudioChunk.TYPE, JammarrPayloads.AudioChunk.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.PlaybackState.TYPE, JammarrPayloads.PlaybackState.CODEC, JammarrNetwork::client);
        registrar.playToClient(JammarrPayloads.ErrorMessage.TYPE, JammarrPayloads.ErrorMessage.CODEC, JammarrNetwork::client);
        registrar.playToServer(JammarrPayloads.ClientHello.TYPE, JammarrPayloads.ClientHello.CODEC, (payload, context) -> {
            if (!protocolMatches(payload.protocolVersion())) {
                context.disconnect(Component.literal("Jammarr protocol mismatch: server requires version " + PROTOCOL));
            } else {
                context.enqueueWork(() -> JammarrServer.instance().hello((ServerPlayer)context.player()));
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
        context.enqueueWork(() -> ClientPayloadBridge.accept(payload));
    }

    private JammarrNetwork() {}
}
