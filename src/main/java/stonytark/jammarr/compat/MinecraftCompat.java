package stonytark.jammarr.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Small compatibility boundary for Minecraft APIs used by shared modern code. */
public final class MinecraftCompat {
    private MinecraftCompat() {}

    public static Component literal(String value) { return Component.literal(value); }
    public static void sendSystemMessage(ServerPlayer player, String message) {
        player.sendSystemMessage(literal(message));
    }
}
