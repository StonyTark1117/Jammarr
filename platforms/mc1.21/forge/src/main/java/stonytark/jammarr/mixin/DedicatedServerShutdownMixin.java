package stonytark.jammarr.mixin;

import com.electronwill.nightconfig.core.file.FileWatcher;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases the non-daemon watcher executor in Forge 51's NightConfig 3.7.3. */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerShutdownMixin {
    @Inject(method = "onServerExit", at = @At("TAIL"))
    private void jammarr$stopConfigWatcher(CallbackInfo callback) {
        FileWatcher.defaultInstance().stop();
    }
}
