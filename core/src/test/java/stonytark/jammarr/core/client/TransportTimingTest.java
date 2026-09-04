package stonytark.jammarr.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTimingTest {
    @Test void staleRepliesAfterRecoveryCannotSkipTheServersNextWindow() {
        for (boolean requestAlreadySent : new boolean[] {false, true}) {
            ChunkWindowTracker tracker = new ChunkWindowTracker(115, 800, 8, 1_500);
            ChunkWindowTracker.Request request = requestAlreadySent
                    ? tracker.request(0, 0, 12_000).get() : null;
            // CI received old request 5, chunks 138..145, after a new manifest
            // at 115. The next three valid windows must still end at 138.
            for (int index = 138; index <= 145; index++) tracker.received(5, index);
            for (int window = 0; window < 3; window++) {
                if (request == null) request = tracker.request(window * 2_000, 0, 12_000).get();
                assertEquals(115 + window * 8, request.startIndex());
                for (int index = request.startIndex(); index < request.startIndex() + request.count(); index++) {
                    java.util.Optional<ChunkWindowTracker.Acknowledgement> ack = tracker.received(request.id(), index);
                    assertEquals(index == request.startIndex() + request.count() - 1, ack.isPresent());
                }
                request = null;
            }
            assertEquals(139, tracker.request(6_000, 0, 12_000).get().startIndex());
        }
    }

    @Test void lateWindowMustStillBeAcknowledgedWhenTheCurrentRetryArrives() {
        ChunkWindowTracker tracker = new ChunkWindowTracker(96, 160, 8, 1_500);
        ChunkWindowTracker.Request original = tracker.request(0, 0, 12_000).get();
        ChunkWindowTracker.Request retry = tracker.request(1_501, 0, 12_000).get();
        // The original response arrives after the timeout changed the request id.
        for (int index = 96; index < 104; index++) {
            assertFalse(tracker.received(original.id(), index).isPresent());
        }
        assertEquals(104, tracker.firstMissing());
        // A matching retry contains accepted duplicates, which must release
        // the window instead of trapping every later request at chunk 96.
        ChunkWindowTracker.Acknowledgement ack = tracker.received(retry.id(), 96).get();
        assertEquals(retry.id(), ack.requestId());
        assertEquals(103, ack.receivedThroughIndex());
        ChunkWindowTracker.Request next = tracker.request(1_502, 0, 12_000).get();
        assertEquals(104, next.startIndex());
    }

    @Test void estimatesClockOffsetFromMidpointAndFiltersJitter() {
        ClockSynchronizer clock = new ClockSynchronizer();
        ClockSynchronizer.Sample first = clock.accept(1_000, 1_150, 1_100);
        assertEquals(100, first.filteredOffsetMs()); assertEquals(1_900, clock.toLocalTime(2_000));
        clock.accept(2_000, 2_500, 2_800);
        assertEquals(100, clock.offsetMs());
        assertEquals(2, clock.sampleCount());
    }

    @Test void replacesAHighLatencyBootstrapSampleWithTheBestRoundTripSample() {
        ClockSynchronizer clock = new ClockSynchronizer();
        clock.accept(1_000, 1_450, 1_600);
        assertEquals(150, clock.offsetMs());
        ClockSynchronizer.Sample better = clock.accept(2_000, 2_120, 2_200);
        assertEquals(20, better.filteredOffsetMs());
        assertEquals(20, clock.offsetMs());
        assertEquals(2, clock.sampleCount());

        clock.reset();
        assertFalse(clock.initialized());
        assertEquals(0, clock.sampleCount());
    }

    @Test void keepsTheLowestLatencySampleAndWaitsForACompleteBootstrap() {
        ClockSynchronizer clock = new ClockSynchronizer();
        clock.accept(1_000, 1_100, 1_100);
        assertEquals(50, clock.offsetMs());
        assertFalse(clock.readyForPlayback());

        // A nearly equivalent RTT can still contain asymmetric server-thread
        // queueing, so it must not pull the established best sample off course.
        clock.accept(2_000, 2_180, 2_120);
        assertEquals(50, clock.offsetMs());
        for (int sample = 2; sample < ClockSynchronizer.STARTUP_SAMPLE_TARGET; sample++) {
            clock.accept(3_000 + sample, 3_070 + sample, 3_100 + sample);
        }
        assertTrue(clock.readyForPlayback());
    }

    @Test void retriesMissingWindowAndAcknowledgesOnlyWhenComplete() {
        ChunkWindowTracker tracker = new ChunkWindowTracker(10, 20, 4, 1_000);
        ChunkWindowTracker.Request first = tracker.request(0, 0, 12_000).get(); assertEquals(10, first.startIndex()); assertEquals(4, first.count());
        assertFalse(tracker.request(500, 0, 12_000).isPresent());
        ChunkWindowTracker.Request retry = tracker.request(1_001, 0, 12_000).get(); assertEquals(10, retry.startIndex());
        assertFalse(tracker.received(retry.id(), 10).isPresent()); assertFalse(tracker.received(retry.id(), 12).isPresent());
        assertFalse(tracker.received(retry.id(), 11).isPresent());
        ChunkWindowTracker.Acknowledgement ack = tracker.received(retry.id(), 13).get(); assertEquals(13, ack.receivedThroughIndex()); assertEquals(14, tracker.firstMissing());
    }

    @Test void pullWindowHonorsMaximumBufferAndDriftThreshold() {
        ChunkWindowTracker tracker = new ChunkWindowTracker(0, 8, 8, 1_000);
        assertFalse(tracker.request(0, 12_000, 12_000).isPresent());
        assertFalse(DriftPolicy.shouldRebuffer(10_000, 10_500, 500));
        assertTrue(DriftPolicy.shouldRebuffer(10_000, 10_501, 500));
    }

    @Test void partialOrRejectedWindowRetriesTheAtomicServerBounds() {
        ChunkWindowTracker tracker = new ChunkWindowTracker(32, 80, 8, 1_000);
        ChunkWindowTracker.Request first = tracker.request(0, 0, 12_000).get();
        assertFalse(tracker.received(first.id(), 32).isPresent());
        assertEquals(33, tracker.firstMissing());

        tracker.reject(first.id());
        ChunkWindowTracker.Request retry = tracker.request(1, 0, 12_000).get();
        assertEquals(32, retry.startIndex());
        assertEquals(8, retry.count());

        for (int index = 33; index < 39; index++) {
            assertFalse(tracker.received(retry.id(), index).isPresent());
        }
        ChunkWindowTracker.Acknowledgement acknowledgement =
                tracker.received(retry.id(), 39).get();
        assertEquals(39, acknowledgement.receivedThroughIndex());
        assertEquals(40, tracker.firstMissing());
    }
}
