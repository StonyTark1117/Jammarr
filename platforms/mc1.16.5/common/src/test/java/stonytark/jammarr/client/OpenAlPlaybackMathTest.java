package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAlPlaybackMathTest {
    @Test void subtractsQueuedPcmAndDeviceLatencyFromSubmittedPcm() {
        long submitted = 44_100L * 8;
        long queued = 44_100L * 4;
        long offsetFixed = Math.round(44_100L * 1.25 * 4_294_967_296.0);

        assertEquals(5_150, OpenAlPlaybackMath.playedMillis(
                submitted, queued, offsetFixed, 100_000_000L, 44_100.0f));
        assertEquals(0, OpenAlPlaybackMath.playedMillis(
                44_100, 44_100, 0, 80_000_000L, 44_100.0f));
        assertEquals(-1, OpenAlPlaybackMath.playedMillis(10, 11, 0, 0, 44_100.0f));
        assertEquals(-1, OpenAlPlaybackMath.playedMillis(
                10, 5, 6L << 32, 0, 44_100.0f));
    }

    @Test void tracksNewestQueuedBuffersAndFailsClosedOnPartialFrames() {
        PcmSubmissionTracker tracker = new PcmSubmissionTracker();
        tracker.submitted(400, 4);
        tracker.submitted(800, 4);
        tracker.submitted(1_200, 4);

        PcmSubmissionTracker.Snapshot snapshot = tracker.snapshot(2);
        assertEquals(600, snapshot.submittedFrames());
        assertEquals(500, snapshot.queuedFrames());
        assertNull(tracker.snapshot(4));

        PcmSubmissionTracker partial = new PcmSubmissionTracker();
        partial.submitted(5, 4);
        assertNull(partial.snapshot(0));
    }

    @Test void backendDriftMustPersistInOneDirection() {
        BackendDriftGuard guard = new BackendDriftGuard(40, 2);
        assertFalse(guard.observe(41));
        assertFalse(guard.observe(-110));
        assertTrue(guard.observe(-105));
        assertFalse(guard.observe(10));
    }
}
