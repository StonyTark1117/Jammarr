package stonytark.jammarr.mixin;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.server.ServerPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.network.LegacyNetwork;

@Mixin(ServerPacketListener.class)
abstract class ServerPacketListenerMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
    private void jammarr$customPayload(CustomPayloadC2SPacket packet, CallbackInfo callback) {
        if (!LegacyNetwork.CHANNEL.equals(packet.channel)) return;
        LegacyNetwork.receiveServer(player, packet.field_2455);
        callback.cancel();
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void jammarr$disconnected(String reason, Object[] details, CallbackInfo callback) {
        LegacyNetwork.playerLeft(player);
        if (Jammarr.coordinator() != null) Jammarr.coordinator().playerLeft(player);
    }
}
