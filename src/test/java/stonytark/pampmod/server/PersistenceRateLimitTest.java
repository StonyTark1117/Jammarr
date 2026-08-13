package stonytark.pampmod.server;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceRateLimitTest {
    @Test void roundTripsQueueOrderAndPlaybackCheckpoint() {
        PampSavedData original = new PampSavedData();
        original.queue().add(new QueueTrack("2", "Second", "Artist", "Album", 2_000));
        original.queue().add(new QueueTrack("1", "First", "Artist", "Album", 1_000));
        original.update(1_234, true);
        CompoundTag tag = original.save(new CompoundTag(), null);
        PampSavedData restored = PampSavedData.load(tag, null);
        assertEquals(2, restored.queue().size()); assertEquals("2", restored.queue().getFirst().key());
        assertEquals(1_234, restored.checkpointMs()); assertTrue(restored.paused());
    }
    @Test void limitsEachPlayerIndependentlyPerSecond() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(limiter.allow(first, 2, 1_000)); assertTrue(limiter.allow(first, 2, 1_100)); assertFalse(limiter.allow(first, 2, 1_200));
        assertTrue(limiter.allow(second, 2, 1_200)); assertTrue(limiter.allow(first, 2, 2_000));
    }
}
