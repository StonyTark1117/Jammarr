package stonytark.jammarr.mixin;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.quilt.QuiltNetworkingCodecRepair;
import stonytark.jammarr.server.JammarrCommands;

@Mixin(value = Commands.class, priority = 1100)
abstract class QuiltServerBootstrapMixin {
    @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target =
            "Lcom/mojang/brigadier/CommandDispatcher;setConsumer(Lcom/mojang/brigadier/ResultConsumer;)V", shift = At.Shift.AFTER))
    private void jammarr$bootstrapQuilt(CallbackInfo callback) {
        QuiltNetworkingCodecRepair.install();
        Jammarr.bootstrapQuilt();
        JammarrCommands.registerQuiltFallback(dispatcher);
    }
}
