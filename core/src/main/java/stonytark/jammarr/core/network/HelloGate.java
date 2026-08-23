package stonytark.jammarr.core.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks the explicit application hello required after a loader accepts a connection. */
public final class HelloGate<K> {
    private final long timeoutTicks;
    private final Map<K, Long> pending = new HashMap<K, Long>();
    private final Set<K> accepted = new HashSet<K>();

    public HelloGate(long timeoutTicks) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        this.timeoutTicks = timeoutTicks;
    }

    public synchronized void require(K key, long currentTick) {
        if (key == null) throw new IllegalArgumentException("key");
        accepted.remove(key);
        pending.put(key, saturatedAdd(currentTick, timeoutTicks));
    }

    public synchronized boolean accept(K key) {
        if (pending.remove(key) == null) return false;
        accepted.add(key);
        return true;
    }

    public synchronized boolean accepted(K key) { return accepted.contains(key); }

    public synchronized void remove(K key) {
        pending.remove(key);
        accepted.remove(key);
    }

    public synchronized List<K> expire(long currentTick) {
        List<K> expired = new ArrayList<K>();
        for (Map.Entry<K, Long> entry : new ArrayList<Map.Entry<K, Long>>(pending.entrySet())) {
            if (currentTick >= entry.getValue()) {
                pending.remove(entry.getKey());
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    public synchronized void clear() {
        pending.clear();
        accepted.clear();
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
