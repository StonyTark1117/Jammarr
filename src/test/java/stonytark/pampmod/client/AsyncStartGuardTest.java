package stonytark.pampmod.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsyncStartGuardTest {
    @Test void permitsOneStartAndCannotLetAStaleCompletionReleaseANewerStart() {
        AsyncStartGuard guard = new AsyncStartGuard();
        long first = guard.begin();
        assertEquals(0, first);
        assertEquals(-1, guard.begin());

        guard.cancel();
        long second = guard.begin();
        assertEquals(1, second);
        assertFalse(guard.complete(first));
        assertTrue(guard.pending());
        assertTrue(guard.complete(second));
        assertFalse(guard.pending());
    }
}
