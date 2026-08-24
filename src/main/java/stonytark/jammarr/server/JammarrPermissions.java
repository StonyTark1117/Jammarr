package stonytark.jammarr.server;

import net.minecraft.server.level.ServerPlayer;

/** Keeps numeric operator-level policy out of the shared coordinator. */
public final class JammarrPermissions {
    public static boolean has(ServerPlayer player, int level) {
        return player.hasPermissions(level);
    }

    private JammarrPermissions() {}
}
