package stonytark.jammarr.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.Jammarr;

@Mixin(ClientPacketListener.class)
public abstract class ClientDisconnectReasonMixin {
    // Forge 43 uses official names in development and SRG names in a release.
    @Inject(method = {"handleDisconnect", "m_6008_"}, at = @At("HEAD"), remap = false)
    private void jammarr$recordDisconnectReason(ClientboundDisconnectPacket packet, CallbackInfo callback) {
        // Connection.disconnect closes the channel before publishing its reason.
        // Forge's logout event can therefore see null; the received packet is
        // the authoritative reason even when those two threads race.
        Jammarr.LOGGER.info("Client disconnected with reason: {}", packet.getReason().getString());
    }
}
