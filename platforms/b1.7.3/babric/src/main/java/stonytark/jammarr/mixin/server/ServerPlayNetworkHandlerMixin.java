package stonytark.jammarr.mixin.server;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.Command;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stonytark.jammarr.server.BabricServerEvents;
import stonytark.jammarr.server.LegacyCommands;

@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerMixin {
    @Shadow private MinecraftServer server;
    @Shadow private ServerPlayerEntity player;

    @Inject(method = "handleCommand", at = @At("HEAD"), cancellable = true)
    private void jammarr$command(String command, CallbackInfo callback) {
        if (LegacyCommands.execute(server, new Command(command, (CommandOutput) (Object) this))) {
            callback.cancel();
        }
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void jammarr$disconnect(String reason, Object[] arguments, CallbackInfo callback) {
        BabricServerEvents.playerLeft(player);
    }
}
