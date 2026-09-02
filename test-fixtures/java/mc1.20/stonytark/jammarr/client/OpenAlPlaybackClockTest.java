package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAlPlaybackClockTest {
    @Test void convertsDeviceLatencyWithoutTruncatingSubMillisecondPrecision() {
        assertEquals(157, OpenAlPlaybackClock.latencyMillis(156_600_000L));
        assertEquals(0, OpenAlPlaybackClock.latencyMillis(400_000L));
        assertEquals(-1, OpenAlPlaybackClock.latencyMillis(-1));
    }

    @Test void subtractsQueuedPcmAndDeviceLatencyFromSubmittedPcm() {
        long submitted = 44_100L * 8;
        long queued = 44_100L * 4;
        long offsetFixed = Math.round(44_100L * 1.25 * 4_294_967_296.0);

        assertEquals(5_150, OpenAlPlaybackClock.calculatePlayedMillis(
                submitted, queued, offsetFixed, 100_000_000L, 44_100.0f));
    }

    @Test void clampsOutputLatencyBeforeTheFirstAudibleFrame() {
        assertEquals(0, OpenAlPlaybackClock.calculatePlayedMillis(
                44_100, 44_100, 0, 80_000_000L, 44_100.0f));
    }

    @Test void rejectsImpossibleQueueAndOffsetMeasurements() {
        assertEquals(-1, OpenAlPlaybackClock.calculatePlayedMillis(10, 11, 0, 0, 44_100.0f));
        assertEquals(-1, OpenAlPlaybackClock.calculatePlayedMillis(
                10, 5, 6L << 32, 0, 44_100.0f));
    }

    @Test void submissionTrackerMapsTheNewestBuffersToTheOpenAlQueue() {
        PcmSubmissionTracker tracker = new PcmSubmissionTracker();
        tracker.submitted(400, 4);
        tracker.submitted(800, 4);
        tracker.submitted(1_200, 4);

        PcmSubmissionTracker.Snapshot snapshot = tracker.snapshot(2);
        assertEquals(600, snapshot.submittedFrames());
        assertEquals(500, snapshot.queuedFrames());
        assertNull(tracker.snapshot(4));
    }

    @Test void submissionTrackerFailsClosedOnPartialFrames() {
        PcmSubmissionTracker tracker = new PcmSubmissionTracker();
        tracker.submitted(5, 4);
        assertNull(tracker.snapshot(0));
    }

    @Test void backendDriftMustPersistAcrossSamplesAndMonotonicTime() {
        BackendDriftGuard guard = new BackendDriftGuard(40, 2, 1_500);
        assertEquals(false, guard.observe(-468, 1_000));
        assertEquals(false, guard.observe(-320, 1_001));
        assertEquals(false, guard.observe(-105, 2_499));
        assertEquals(true, guard.observe(-105, 2_500));
        assertEquals(false, guard.observe(10, 3_000));
        guard.reset();
        assertEquals(false, guard.observe(-105, 4_000));
        assertEquals(false, guard.observe(105, 6_000));
    }
}
