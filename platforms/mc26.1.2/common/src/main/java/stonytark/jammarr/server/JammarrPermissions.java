package stonytark.jammarr.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/** Bridges the numeric Jammarr operator setting onto 26.1's permission objects. */
public final class JammarrPermissions {
    public static boolean has(ServerPlayer player, int level) {
        return player.permissions().hasPermission(permission(level));
    }

    public static boolean has(CommandSourceStack source, int level) {
        return source.permissions().hasPermission(permission(level));
    }

    private static Permission permission(int level) {
        return new Permission.HasCommandLevel(PermissionLevel.byId(level));
    }

    private JammarrPermissions() {}
}
