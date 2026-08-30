package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyBackendQueueGuardTest {
    @Test void recoversBeforeFeedingAStoppedMidTrackBackend() {
        assertTrue(LegacyBackendQueueGuard.shouldRecover(false, false, false, 2_000L, 1_000L));
    }

    @Test void permitsAsynchronousStartAndResumeToSettle() {
        assertFalse(LegacyBackendQueueGuard.shouldRecover(false, false, false, 999L, 1_000L));
    }

    @Test void doesNotRecoverPausedFinishedOrPlayingStreams() {
        assertFalse(LegacyBackendQueueGuard.shouldRecover(true, false, false, 2_000L, 1_000L));
        assertFalse(LegacyBackendQueueGuard.shouldRecover(false, true, false, 2_000L, 1_000L));
        assertFalse(LegacyBackendQueueGuard.shouldRecover(false, false, true, 2_000L, 1_000L));
    }
}
