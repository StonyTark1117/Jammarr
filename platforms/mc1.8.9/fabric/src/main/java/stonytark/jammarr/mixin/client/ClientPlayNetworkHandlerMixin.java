package stonytark.jammarr.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.DisconnectS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.LegacyClient;
import stonytark.jammarr.Jammarr;

@Mixin(ClientPlayNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void jammarr$logDisconnectReason(DisconnectS2CPacket packet, CallbackInfo callback) {
        // Minecraft closes the channel before publishing its reason. The
        // disconnect event can therefore see only a generic transport loss.
        Jammarr.LOGGER.info("Client disconnected with reason: {}", packet.getReason().asUnformattedString());
    }

    @Inject(method = "onChatMessage", at = @At("HEAD"))
    private void jammarr$acceptanceChat(ChatMessageS2CPacket packet, CallbackInfo callback) {
        LegacyClient.acceptanceChat(packet.getMessage());
    }
}
