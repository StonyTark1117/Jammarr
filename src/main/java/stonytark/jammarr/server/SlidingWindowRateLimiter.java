package stonytark.jammarr.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SlidingWindowRateLimiter {
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(UUID subject, int perSecond, long nowMs) {
        long second = nowMs / 1_000;
        Window value = windows.compute(subject, (id, old) -> old == null || old.second != second
                ? new Window(second, 1) : new Window(second, old.count + 1));
        return value.count <= perSecond;
    }

    public int count(UUID subject, long nowMs) {
        Window value = windows.get(subject);
        return value == null || value.second != nowMs / 1_000 ? 0 : value.count;
    }

    public void remove(UUID subject) { windows.remove(subject); }
    private record Window(long second, int count) {}
}
