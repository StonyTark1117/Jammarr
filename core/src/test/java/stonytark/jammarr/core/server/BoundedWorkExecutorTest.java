package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedWorkExecutorTest {
    @Test void rejectsBeyondTheFixedWorkerAndQueueCapacity() throws Exception {
        BoundedWorkExecutor executor = new BoundedWorkExecutor(1, 1, "bounded-test-");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> running = executor.supply(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return "running";
            });
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            CompletableFuture<String> queued = executor.supply(() -> "queued");
            CompletableFuture<String> rejected = executor.supply(() -> "rejected");

            ExecutionException failure = assertThrows(ExecutionException.class, rejected::get);
            assertInstanceOf(BoundedWorkExecutor.WorkQueueFullException.class, failure.getCause());
            assertEquals(1, executor.queuedTasks());
            assertEquals(1L, executor.rejectedTasks());

            release.countDown();
            assertEquals("running", running.get(2L, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(2L, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test void convertsTaskFailuresIntoExceptionalFutures() {
        BoundedWorkExecutor executor = new BoundedWorkExecutor(1, 1, "failure-test-");
        try {
            CompletableFuture<String> failed = executor.supply(() -> {
                throw new IllegalStateException("boom");
            });
            ExecutionException failure = assertThrows(ExecutionException.class, failed::get);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            executor.close();
        }
    }
}
