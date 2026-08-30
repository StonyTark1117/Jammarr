package stonytark.jammarr.core.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks whether a connected player can safely receive Jammarr payloads.
 * Missing clients are classified as absent rather than rejected; only an
 * explicit, matching application hello enables audio and control traffic.
 */
public final class ClientCapabilityRegistry<K> {
    public enum State { UNKNOWN, CAPABLE, ABSENT, INCOMPATIBLE }

    private final long discoveryTicks;
    private final Map<K, State> states = new HashMap<K, State>();
    private final Map<K, Long> deadlines = new HashMap<K, Long>();

    public ClientCapabilityRegistry(long discoveryTicks) {
        if (discoveryTicks < 1L) throw new IllegalArgumentException("discoveryTicks must be positive");
        this.discoveryTicks = discoveryTicks;
    }

    public synchronized void connected(K key, long currentTick, boolean channelAdvertised) {
        if (key == null) throw new IllegalArgumentException("key");
        states.put(key, channelAdvertised ? State.UNKNOWN : State.ABSENT);
        if (channelAdvertised) deadlines.put(key, saturatedAdd(currentTick, discoveryTicks));
        else deadlines.remove(key);
    }

    /** Returns true only when this hello newly activates the connection. */
    public synchronized boolean accept(K key, int offeredProtocol, int requiredProtocol) {
        if (key == null) throw new IllegalArgumentException("key");
        deadlines.remove(key);
        if (offeredProtocol != requiredProtocol) {
            states.put(key, State.INCOMPATIBLE);
            return false;
        }
        State previous = states.put(key, State.CAPABLE);
        return previous != State.CAPABLE;
    }

    /** Marks discovery-only peers absent without disconnecting them. */
    public synchronized List<K> expire(long currentTick) {
        List<K> expired = new ArrayList<K>();
        for (Map.Entry<K, Long> entry : new ArrayList<Map.Entry<K, Long>>(deadlines.entrySet())) {
            if (currentTick >= entry.getValue()) {
                K key = entry.getKey();
                deadlines.remove(key);
                if (states.get(key) == State.UNKNOWN) {
                    states.put(key, State.ABSENT);
                    expired.add(key);
                }
            }
        }
        return expired;
    }

    public synchronized State state(K key) {
        State state = states.get(key);
        return state == null ? State.ABSENT : state;
    }

    public synchronized boolean capable(K key) { return state(key) == State.CAPABLE; }

    public synchronized int count(State state) {
        int count = 0;
        for (State value : states.values()) if (value == state) count++;
        return count;
    }

    public synchronized void remove(K key) {
        states.remove(key);
        deadlines.remove(key);
    }

    public synchronized void clear() {
        states.clear();
        deadlines.clear();
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
