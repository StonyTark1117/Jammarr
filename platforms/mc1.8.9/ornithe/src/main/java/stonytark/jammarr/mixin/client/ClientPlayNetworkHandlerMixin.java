package stonytark.jammarr.mixin.client;

import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.LegacyClient;

@Mixin(ClientPlayNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "handleChatMessage", at = @At("HEAD"))
    private void jammarr$acceptanceChat(ChatMessageS2CPacket packet, CallbackInfo callback) {
        LegacyClient.acceptanceChat(packet.getMessage());
    }
}
