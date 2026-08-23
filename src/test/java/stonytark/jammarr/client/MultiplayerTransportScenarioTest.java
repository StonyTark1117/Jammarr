package stonytark.jammarr.client;

import stonytark.jammarr.core.client.ChunkWindowTracker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerTransportScenarioTest {
    @Test
    void twoListenersMaintainIndependentWindows() {
        ChunkWindowTracker firstListener = new ChunkWindowTracker(0, 16, 8, 1_000);
        ChunkWindowTracker secondListener = new ChunkWindowTracker(0, 16, 8, 1_000);
        ChunkWindowTracker.Request firstRequest = firstListener.request(0, 0, 12_000).orElseThrow();
        ChunkWindowTracker.Request secondRequest = secondListener.request(0, 0, 12_000).orElseThrow();

        for (int index = 0; index < 8; index++) {
            firstListener.received(firstRequest.id(), index);
            secondListener.received(secondRequest.id(), index);
        }

        assertEquals(8, firstListener.firstMissing());
        assertEquals(8, secondListener.firstMissing());
        assertEquals(1, firstRequest.id());
        assertEquals(1, secondRequest.id());
    }

    @Test
    void lateJoinStartsAtAuthoritativePlaybackWindow() {
        ChunkWindowTracker lateListener = new ChunkWindowTracker(12, 20, 4, 1_000);
        ChunkWindowTracker.Request request = lateListener.request(0, 0, 12_000).orElseThrow();

        assertEquals(12, request.startIndex());
        assertEquals(4, request.count());
    }

    @Test
    void droppedWindowRetriesAndReconnectStartsFresh() {
        ChunkWindowTracker original = new ChunkWindowTracker(4, 12, 4, 1_000);
        ChunkWindowTracker.Request lost = original.request(0, 0, 12_000).orElseThrow();
        original.reject(lost.id());
        ChunkWindowTracker.Request retry = original.request(1, 0, 12_000).orElseThrow();
        assertEquals(4, retry.startIndex());
        assertTrue(retry.id() > lost.id());

        ChunkWindowTracker reconnected = new ChunkWindowTracker(8, 12, 4, 1_000);
        ChunkWindowTracker.Request reconnectRequest = reconnected.request(0, 0, 12_000).orElseThrow();
        assertEquals(8, reconnectRequest.startIndex());
    }
}
