package stonytark.jammarr.mixin.server;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.server.BabricServerEvents;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void jammarr$shutdown(CallbackInfo callback) { BabricServerEvents.shutdown(); }
}
