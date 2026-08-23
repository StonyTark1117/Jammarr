package stonytark.jammarr.server;

import stonytark.jammarr.network.JammarrPayloads;

final class StationControlPolicy {
    enum Decision { ALLOW, PERMISSION_DENIED, STALE_GENERATION }

    static Decision assess(boolean operator, long expectedGeneration, long currentGeneration) {
        if (!operator) return Decision.PERMISSION_DENIED;
        return expectedGeneration == currentGeneration ? Decision.ALLOW : Decision.STALE_GENERATION;
    }

    static boolean replacesCurrentPlayback(JammarrPayloads.StationAction action) {
        return action == JammarrPayloads.StationAction.START_NOW;
    }

    private StationControlPolicy() {}
}
