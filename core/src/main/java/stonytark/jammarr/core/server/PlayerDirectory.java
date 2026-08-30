package stonytark.jammarr.core.server;

import java.util.List;
import java.util.UUID;

/** Loader adapter for connected player identity, permissions, and chat feedback. */
public interface PlayerDirectory<P> {
    UUID playerId(P player);
    boolean isOperator(P player, int permissionLevel);
    List<P> players();
    int playerCount();
    default int totalPlayerCount() { return playerCount(); }
    void chat(P player, String message);
}
