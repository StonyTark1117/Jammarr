package stonytark.jammarr.core.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded round-robin egress queue. Batches are admitted atomically so a
 * rejected chunk window can be retried without leaving a partial window.
 */
public final class FairEgressScheduler<K, P, M> {
    private final int maxItemsPerKey;
    private final int maxItems;
    private final long maxBytes;
    private final Map<K, QueueState<P, M>> queues = new LinkedHashMap<K, QueueState<P, M>>();
    private final Deque<K> active = new ArrayDeque<K>();
    private int backlogItems;
    private long backlogBytes;
    private long rejectedBatches;

    public FairEgressScheduler(int maxItemsPerKey, int maxItems, long maxBytes) {
        if (maxItemsPerKey <= 0 || maxItems <= 0 || maxBytes <= 0L) {
            throw new IllegalArgumentException("egress limits must be positive");
        }
        this.maxItemsPerKey = maxItemsPerKey;
        this.maxItems = maxItems;
        this.maxBytes = maxBytes;
    }

    public boolean enqueueBatch(K key, P player, List<Item<M>> batch) {
        if (key == null || player == null || batch == null) throw new IllegalArgumentException("egress batch");
        if (batch.isEmpty()) return true;
        long batchBytes = 0L;
        for (Item<M> item : batch) {
            if (item == null) throw new IllegalArgumentException("egress item");
            batchBytes = saturatedAdd(batchBytes, item.sizeBytes());
        }
        QueueState<P, M> queue = queues.get(key);
        int keyItems = queue == null ? 0 : queue.items.size();
        if (batch.size() > maxItemsPerKey - keyItems
                || batch.size() > maxItems - backlogItems
                || batchBytes > maxBytes - backlogBytes) {
            rejectedBatches++;
            return false;
        }
        if (queue == null) {
            queue = new QueueState<P, M>(player);
            queues.put(key, queue);
            active.addLast(key);
        } else {
            queue.player = player;
        }
        queue.items.addAll(batch);
        backlogItems += batch.size();
        backlogBytes += batchBytes;
        return true;
    }

    public int drain(int maxDrainItems, long maxDrainBytes, Sender<P, M> sender) {
        if (maxDrainItems <= 0 || maxDrainBytes <= 0L || sender == null) return 0;
        int drained = 0;
        long drainedBytes = 0L;
        while (drained < maxDrainItems && !active.isEmpty()) {
            int cycle = active.size();
            boolean madeProgress = false;
            for (int index = 0; index < cycle && drained < maxDrainItems; index++) {
                K key = active.removeFirst();
                QueueState<P, M> queue = queues.get(key);
                if (queue == null || queue.items.isEmpty()) continue;
                Item<M> item = queue.items.peekFirst();
                if (item.sizeBytes() <= maxDrainBytes - drainedBytes) {
                    queue.items.removeFirst();
                    backlogItems--;
                    backlogBytes -= item.sizeBytes();
                    sender.send(queue.player, item.message());
                    drained++;
                    drainedBytes += item.sizeBytes();
                    madeProgress = true;
                }
                if (queue.items.isEmpty()) queues.remove(key);
                else active.addLast(key);
            }
            if (!madeProgress) break;
        }
        return drained;
    }

    public void remove(K key) {
        QueueState<P, M> queue = queues.remove(key);
        if (queue == null) return;
        active.remove(key);
        backlogItems -= queue.items.size();
        for (Item<M> item : queue.items) backlogBytes -= item.sizeBytes();
    }

    public void clear() {
        queues.clear();
        active.clear();
        backlogItems = 0;
        backlogBytes = 0L;
    }

    public int backlogItems() { return backlogItems; }
    public long backlogBytes() { return backlogBytes; }
    public long rejectedBatches() { return rejectedBatches; }

    private static long saturatedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    public interface Sender<P, M> {
        void send(P player, M message);
    }

    public static final class Item<M> {
        private final M message;
        private final int sizeBytes;

        public Item(M message, int sizeBytes) {
            if (message == null || sizeBytes < 0) throw new IllegalArgumentException("egress item");
            this.message = message;
            this.sizeBytes = sizeBytes;
        }

        public M message() { return message; }
        public int sizeBytes() { return sizeBytes; }
    }

    private static final class QueueState<P, M> {
        private P player;
        private final Deque<Item<M>> items = new ArrayDeque<Item<M>>();
        private QueueState(P player) { this.player = player; }
    }
}
