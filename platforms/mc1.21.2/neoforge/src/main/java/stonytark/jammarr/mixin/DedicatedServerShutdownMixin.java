package stonytark.jammarr.mixin;

import com.electronwill.nightconfig.core.file.FileWatcher;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Backports NeoForge #1757 to 1.21.2, which still ships NightConfig 3.8.0.
 * https://github.com/neoforged/NeoForge/pull/1757
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerShutdownMixin {
    @Inject(method = "onServerExit", at = @At("TAIL"))
    private void jammarr$stopConfigWatcher(CallbackInfo callback) {
        // A file change creates a non-daemon executor in this NightConfig version.
        // Stop it after dedicated-server cleanup; integrated servers keep watching.
        FileWatcher.defaultInstance().stop();
    }
}
