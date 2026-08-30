package stonytark.jammarr.core.server;

import java.util.List;
import java.util.UUID;

/** Java-7 class-file form of the shared interface for Forge 1.6.4's ASM 4 scanner. */
public interface PlayerDirectory<P> {
    UUID playerId(P player);
    boolean isOperator(P player, int permissionLevel);
    List<P> players();
    int playerCount();
    int totalPlayerCount();
    void chat(P player, String message);
}
