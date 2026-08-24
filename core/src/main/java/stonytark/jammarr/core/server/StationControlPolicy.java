package stonytark.jammarr.core.server;

import stonytark.jammarr.core.protocol.ControlPackets;

/** Shared permission, optimistic-generation, and replacement rules for station controls. */
public final class StationControlPolicy {
    public enum Decision { ALLOW, PERMISSION_DENIED, STALE_GENERATION }

    public static Decision assess(boolean operator, long expectedGeneration, long currentGeneration) {
        if (!operator) return Decision.PERMISSION_DENIED;
        return expectedGeneration == currentGeneration ? Decision.ALLOW : Decision.STALE_GENERATION;
    }

    public static boolean replacesCurrentPlayback(ControlPackets.StationAction action) {
        return action == ControlPackets.StationAction.START_NOW;
    }

    private StationControlPolicy() {}
}
