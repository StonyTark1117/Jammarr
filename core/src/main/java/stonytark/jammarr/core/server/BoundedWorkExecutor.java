package stonytark.jammarr.core.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Fixed worker pool with an explicitly bounded backlog and observable rejection count. */
public final class BoundedWorkExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejected = new AtomicLong();

    public BoundedWorkExecutor(int threads, int queueCapacity, final String threadPrefix) {
        if (threads <= 0 || queueCapacity <= 0 || threadPrefix == null || threadPrefix.isEmpty()) {
            throw new IllegalArgumentException("worker limits");
        }
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, threadPrefix + sequence.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> supply(final Supplier<T> supplier) {
        if (supplier == null) throw new IllegalArgumentException("supplier");
        final CompletableFuture<T> future = new CompletableFuture<T>();
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try { future.complete(supplier.get()); }
                    catch (Throwable error) { future.completeExceptionally(error); }
                }
            });
        } catch (RejectedExecutionException full) {
            rejected.incrementAndGet();
            future.completeExceptionally(new WorkQueueFullException(
                    "Jammarr background work queue is full", full));
        }
        return future;
    }

    public int queuedTasks() { return executor.getQueue().size(); }
    public int activeTasks() { return executor.getActiveCount(); }
    public long rejectedTasks() { return rejected.get(); }

    @Override public void close() { executor.shutdownNow(); }

    public static final class WorkQueueFullException extends RuntimeException {
        public WorkQueueFullException(String message, Throwable cause) { super(message, cause); }
    }
}
