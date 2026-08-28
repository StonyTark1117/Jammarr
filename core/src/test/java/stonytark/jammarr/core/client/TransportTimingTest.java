package stonytark.jammarr.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTimingTest {
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
}
