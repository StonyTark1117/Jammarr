package stonytark.jammarr.mixin.client;

import net.minecraft.client.network.ClientNetworkHandler;
import net.minecraft.network.packet.play.ChatMessagePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.LegacyClient;

@Mixin(ClientNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void jammarr$acceptanceChat(ChatMessagePacket packet, CallbackInfo callback) {
        if (LegacyClient.receiveChat(packet.chatMessage)) callback.cancel();
    }
}
