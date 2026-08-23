package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportTimingTest {
    @Test void estimatesClockOffsetFromMidpointAndFiltersJitter() {
        ClockSynchronizer clock = new ClockSynchronizer();
        ClockSynchronizer.Sample first = clock.accept(1_000, 1_150, 1_100);
        assertEquals(100, first.filteredOffsetMs()); assertEquals(1_900, clock.toLocalTime(2_000));
        clock.accept(2_000, 2_500, 2_800); // high latency sample must not displace the best estimate
        assertEquals(100, clock.offsetMs());
    }
    @Test void retriesMissingWindowAndAcknowledgesOnlyWhenComplete() {
        ChunkWindowTracker tracker = new ChunkWindowTracker(10, 20, 4, 1_000);
        var first = tracker.request(0, 0, 12_000).orElseThrow(); assertEquals(10, first.startIndex()); assertEquals(4, first.count());
        assertTrue(tracker.request(500, 0, 12_000).isEmpty());
        var retry = tracker.request(1_001, 0, 12_000).orElseThrow(); assertEquals(10, retry.startIndex());
        assertTrue(tracker.received(retry.id(), 10).isEmpty()); assertTrue(tracker.received(retry.id(), 12).isEmpty());
        assertTrue(tracker.received(retry.id(), 11).isEmpty());
        var ack = tracker.received(retry.id(), 13).orElseThrow(); assertEquals(13, ack.receivedThroughIndex()); assertEquals(14, tracker.firstMissing());
    }
    @Test void pullWindowHonorsMaximumBufferAndDriftThreshold() {
        ChunkWindowTracker tracker = new ChunkWindowTracker(0, 8, 8, 1_000);
        assertTrue(tracker.request(0, 12_000, 12_000).isEmpty());
        assertFalse(DriftPolicy.shouldRebuffer(10_000, 10_500, 500));
        assertTrue(DriftPolicy.shouldRebuffer(10_000, 10_501, 500));
    }
}
