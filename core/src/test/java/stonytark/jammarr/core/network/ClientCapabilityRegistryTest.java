package stonytark.jammarr.core.network;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCapabilityRegistryTest {
    @Test void missingChannelIsAbsentAndNeverBecomesPending() {
        ClientCapabilityRegistry<String> registry = new ClientCapabilityRegistry<String>(1200L);
        registry.connected("vanilla", 10L, false);
        assertEquals(ClientCapabilityRegistry.State.ABSENT, registry.state("vanilla"));
        assertTrue(registry.expire(10_000L).isEmpty());
        assertFalse(registry.capable("vanilla"));
    }

    @Test void matchingHelloActivatesExactlyOnce() {
        ClientCapabilityRegistry<String> registry = new ClientCapabilityRegistry<String>(1200L);
        registry.connected("modded", 0L, true);
        assertEquals(ClientCapabilityRegistry.State.UNKNOWN, registry.state("modded"));
        assertTrue(registry.accept("modded", 6, 6));
        assertFalse(registry.accept("modded", 6, 6));
        assertTrue(registry.capable("modded"));
    }

    @Test void timeoutClassifiesAbsentWithoutPreventingLateActivation() {
        ClientCapabilityRegistry<String> registry = new ClientCapabilityRegistry<String>(1200L);
        registry.connected("slow", 0L, true);
        assertEquals(Collections.singletonList("slow"), registry.expire(1200L));
        assertEquals(ClientCapabilityRegistry.State.ABSENT, registry.state("slow"));
        assertTrue(registry.accept("slow", 6, 6));
        assertTrue(registry.capable("slow"));
    }

    @Test void mismatchedAndDisconnectedPeersCannotReceiveTraffic() {
        ClientCapabilityRegistry<String> registry = new ClientCapabilityRegistry<String>(1200L);
        registry.connected("old", 0L, true);
        assertFalse(registry.accept("old", 5, 6));
        assertEquals(ClientCapabilityRegistry.State.INCOMPATIBLE, registry.state("old"));
        assertFalse(registry.capable("old"));
        registry.remove("old");
        assertEquals(ClientCapabilityRegistry.State.ABSENT, registry.state("old"));
    }

    @Test void reconnectReplacesCapabilityAndDeadlineState() {
        ClientCapabilityRegistry<String> registry = new ClientCapabilityRegistry<String>(20L);
        registry.connected("player", 0L, true);
        assertTrue(registry.accept("player", 6, 6));
        registry.connected("player", 10L, false);
        assertFalse(registry.capable("player"));
        assertEquals(ClientCapabilityRegistry.State.ABSENT, registry.state("player"));
        assertEquals(0, registry.count(ClientCapabilityRegistry.State.CAPABLE));
        assertEquals(1, registry.count(ClientCapabilityRegistry.State.ABSENT));
    }

    @Test void saturatedDeadlineExpiresWithoutOverflow() {
        ClientCapabilityRegistry<String> registry = new ClientCapabilityRegistry<String>(20L);
        registry.connected("player", Long.MAX_VALUE - 1L, true);
        assertTrue(registry.expire(Long.MAX_VALUE - 1L).isEmpty());
        assertEquals(Collections.singletonList("player"), registry.expire(Long.MAX_VALUE));
    }
}
