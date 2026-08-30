package stonytark.jammarr.mixin.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.Command;
import net.minecraft.server.command.ServerCommandHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.server.LegacyCommands;

@Mixin(ServerCommandHandler.class)
abstract class ServerCommandHandlerMixin {
    @Shadow private MinecraftServer server;

    @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true)
    private void jammarr$command(Command command, CallbackInfo callback) {
        if (LegacyCommands.execute(server, command)) callback.cancel();
    }
}
