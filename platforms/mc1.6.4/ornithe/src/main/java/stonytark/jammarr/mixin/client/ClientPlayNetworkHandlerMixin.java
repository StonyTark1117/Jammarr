package stonytark.jammarr.mixin.client;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.packet.ChatMessagePacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.LegacyClient;

@Mixin(ClientNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "handleChatMessage", at = @At("HEAD"))
    private void jammarr$acceptanceChat(ChatMessagePacket packet, CallbackInfo callback) {
        LegacyClient.acceptanceChat(Text.deserialize(packet.message));
    }
}
