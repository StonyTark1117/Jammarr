package stonytark.jammarr.core.network;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class HelloGateTest {
    @Test void acceptsOnlyPendingPeers() {
        HelloGate<String> gate = new HelloGate<String>(100);
        assertFalse(gate.accept("player"));
        gate.require("player", 20);
        assertFalse(gate.accepted("player"));
        assertTrue(gate.accept("player"));
        assertTrue(gate.accepted("player"));
        assertFalse(gate.accept("player"));
    }

    @Test void expiresAtDeadlineAndCanBeCleared() {
        HelloGate<String> gate = new HelloGate<String>(5);
        gate.require("one", 10);
        assertTrue(gate.expire(14).isEmpty());
        assertEquals(Collections.singletonList("one"), gate.expire(15));
        assertFalse(gate.accepted("one"));
        gate.require("two", Long.MAX_VALUE - 1);
        assertTrue(gate.expire(Long.MAX_VALUE - 1).isEmpty());
        gate.clear();
        assertFalse(gate.accept("two"));
    }

    @Test void disconnectRemovesPendingAndAcceptedState() {
        HelloGate<String> gate = new HelloGate<String>(20);
        gate.require("pending", 0);
        gate.remove("pending");
        assertTrue(gate.expire(100).isEmpty());
        gate.require("accepted", 0);
        assertTrue(gate.accept("accepted"));
        gate.remove("accepted");
        assertFalse(gate.accepted("accepted"));
    }
}
