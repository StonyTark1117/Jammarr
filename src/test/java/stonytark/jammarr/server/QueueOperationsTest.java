package stonytark.jammarr.server;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueueOperationsTest {
    @Test void appendsOnlyAvailableCapacity() {
        List<QueueTrack> queue = new ArrayList<>(List.of(track("1")));
        QueueOperations.AppendResult result = QueueOperations.append(queue, List.of(track("2"), track("3")), 2);
        assertEquals(QueueOperations.Result.APPLIED, result.result()); assertEquals(1, result.accepted()); assertEquals(2, queue.size());
        assertEquals(QueueOperations.Result.FULL, QueueOperations.append(queue, List.of(track("4")), 2).result());
    }
    @Test void restrictsMutationAndProtectsCurrentTrackFromReorder() {
        List<QueueTrack> queue = new ArrayList<>(List.of(track("1"), track("2"), track("3")));
        assertEquals(QueueOperations.Result.PERMISSION_DENIED, QueueOperations.move(queue, 2, -1, false));
        assertEquals(QueueOperations.Result.INVALID_INDEX, QueueOperations.move(queue, 1, -1, true));
        assertEquals(QueueOperations.Result.APPLIED, QueueOperations.move(queue, 2, -1, true));
        assertEquals("3", queue.get(1).key());
        assertEquals(QueueOperations.Result.PERMISSION_DENIED, QueueOperations.remove(queue, 1, false));
    }
    private static QueueTrack track(String key) { return new QueueTrack(key, "Track " + key, "Artist", "Album", 1_000); }
}
