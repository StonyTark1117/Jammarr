package stonytark.jammarr.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.quilt.QuiltNetworkingCodecRepair;

@Mixin(MinecraftServer.class)
abstract class QuiltServerBootstrapMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void jammarr$bootstrapQuilt(CallbackInfo callback) {
        QuiltNetworkingCodecRepair.install();
        Jammarr.bootstrapQuilt();
    }
}
