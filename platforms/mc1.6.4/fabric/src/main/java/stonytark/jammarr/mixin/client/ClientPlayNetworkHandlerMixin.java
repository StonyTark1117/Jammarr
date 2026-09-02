package stonytark.jammarr.mixin.client;

import net.minecraft.client.network.ClientLoopbackPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.LegacyClient;
import stonytark.jammarr.network.LegacyNetwork;

@Mixin(ClientLoopbackPlayNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void jammarr$joined(GameJoinS2CPacket packet, CallbackInfo callback) {
        LegacyClient.loginSucceeded();
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void jammarr$disconnected(String reason, Object[] details, CallbackInfo callback) {
        LegacyClient.disconnected(reason, details);
    }

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void jammarr$acceptanceChat(ChatMessageS2CPacket packet, CallbackInfo callback) {
        if (LegacyClient.receiveChat(packet.message)) callback.cancel();
    }

    @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
    private void jammarr$customPayload(CustomPayloadC2SPacket packet, CallbackInfo callback) {
        if (!LegacyNetwork.CHANNEL.equals(packet.channel)) return;
        LegacyNetwork.receiveClient(packet.field_2455);
        callback.cancel();
    }
}
