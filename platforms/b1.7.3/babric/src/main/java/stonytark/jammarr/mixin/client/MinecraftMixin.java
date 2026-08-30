package stonytark.jammarr.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.client.LegacyClient;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void jammarr$clientTick(CallbackInfo callback) {
        LegacyClient.clientTick();
    }

    @Inject(method = "forceResourceReload", at = @At("TAIL"))
    private void jammarr$resourceReloaded(CallbackInfo callback) {
        LegacyClient.resourceReloaded();
    }
}
