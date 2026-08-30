package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairEgressSchedulerTest {
    @Test void drainsOneItemPerListenerInRoundRobinOrder() {
        FairEgressScheduler<String, String, String> scheduler = scheduler(8, 16, 1_024L);
        scheduler.enqueueBatch("a", "A", items("A1", "A2", "A3"));
        scheduler.enqueueBatch("b", "B", items("B1", "B2"));
        scheduler.enqueueBatch("c", "C", items("C1"));
        final List<String> sent = new ArrayList<String>();

        assertEquals(6, scheduler.drain(16, 1_024L, (player, message) -> sent.add(message)));

        assertEquals(Arrays.asList("A1", "B1", "C1", "A2", "B2", "A3"), sent);
        assertEquals(0, scheduler.backlogItems());
        assertEquals(0L, scheduler.backlogBytes());
    }

    @Test void rejectsOverloadWithoutAdmittingPartOfTheBatch() {
        FairEgressScheduler<String, String, String> scheduler = scheduler(2, 3, 12L);
        assertTrue(scheduler.enqueueBatch("a", "A", items("A1", "A2")));
        assertFalse(scheduler.enqueueBatch("a", "A", items("A3")));
        assertFalse(scheduler.enqueueBatch("b", "B", items("B1", "B2")));

        assertEquals(2, scheduler.backlogItems());
        assertEquals(4L, scheduler.backlogBytes());
        assertEquals(2L, scheduler.rejectedBatches());
    }

    @Test void respectsByteBudgetAndLeavesOversizedHeadQueued() {
        FairEgressScheduler<String, String, String> scheduler = scheduler(8, 16, 1_024L);
        scheduler.enqueueBatch("a", "A", Collections.singletonList(
                new FairEgressScheduler.Item<String>("large", 9)));
        scheduler.enqueueBatch("b", "B", Collections.singletonList(
                new FairEgressScheduler.Item<String>("small", 4)));
        final List<String> sent = new ArrayList<String>();

        assertEquals(1, scheduler.drain(8, 5L, (player, message) -> sent.add(message)));
        assertEquals(Collections.singletonList("small"), sent);
        assertEquals(1, scheduler.backlogItems());
        assertEquals(9L, scheduler.backlogBytes());
    }

    @Test void removeAndClearKeepAccountingExact() {
        FairEgressScheduler<String, String, String> scheduler = scheduler(8, 16, 1_024L);
        scheduler.enqueueBatch("a", "A", items("A1", "A2"));
        scheduler.enqueueBatch("b", "B", items("B1"));
        scheduler.remove("a");
        assertEquals(1, scheduler.backlogItems());
        assertEquals(2L, scheduler.backlogBytes());

        scheduler.clear();
        assertEquals(0, scheduler.backlogItems());
        assertEquals(0L, scheduler.backlogBytes());
    }

    private static FairEgressScheduler<String, String, String> scheduler(
            int perKey, int total, long bytes) {
        return new FairEgressScheduler<String, String, String>(perKey, total, bytes);
    }

    private static List<FairEgressScheduler.Item<String>> items(String... values) {
        List<FairEgressScheduler.Item<String>> result = new ArrayList<FairEgressScheduler.Item<String>>();
        for (String value : values) result.add(new FairEgressScheduler.Item<String>(value, 2));
        return result;
    }
}
