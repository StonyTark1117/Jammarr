package stonytark.jammarr.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.JammarrClient;

@Mixin(Minecraft.class)
abstract class QuiltClientBootstrapMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void jammarr$bootstrapQuilt(CallbackInfo callback) {
        JammarrClient.bootstrapQuilt();
    }
}
