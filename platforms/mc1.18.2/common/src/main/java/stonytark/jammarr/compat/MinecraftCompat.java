package stonytark.jammarr.compat;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft 1.18.2 implementation of the shared modern compatibility boundary. */
public final class MinecraftCompat {
    private MinecraftCompat() {}

    public static Component literal(String value) { return new TextComponent(value); }
    public static Component translatable(String key, Object... arguments) { return new TranslatableComponent(key, arguments); }
    public static Component empty() { return TextComponent.EMPTY; }
    public static void sendSystemMessage(ServerPlayer player, String message) {
        player.sendMessage(literal(message), Util.NIL_UUID);
    }
}
