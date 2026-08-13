package stonytark.pampmod.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HardeningPolicyTest {
    @Test void transferRequiresTheManifestIndexAndACompleteAcknowledgement() {
        UUID session = UUID.randomUUID();
        ChunkTransferPolicy.State initial = ChunkTransferPolicy.initial(session, 10, 1_000);
        assertFalse(ChunkTransferPolicy.acceptsRequest(initial, session, 1, 9, 8, 40, 1_000));
        assertTrue(ChunkTransferPolicy.acceptsRequest(initial, session, 1, 10, 8, 40, 1_000));

        ChunkTransferPolicy.State inFlight = ChunkTransferPolicy.begin(session, 1, 10, 8, 1_000);
        assertFalse(ChunkTransferPolicy.acceptsRequest(inFlight, session, 2, 18, 8, 40, 1_100));
        assertTrue(ChunkTransferPolicy.acknowledge(inFlight, session, 1, 16, 2_000, 1_200).isEmpty());
        ChunkTransferPolicy.State acknowledged = ChunkTransferPolicy.acknowledge(inFlight, session, 1, 17, 2_000, 1_200).orElseThrow();
        assertTrue(ChunkTransferPolicy.acceptsRequest(acknowledged, session, 2, 18, 8, 40, 1_201));
    }

    @Test void transferRetriesOnlyTheSameWindowAndWaitsForReportedBufferToDrain() {
        UUID session = UUID.randomUUID();
        ChunkTransferPolicy.State inFlight = ChunkTransferPolicy.begin(session, 4, 0, 8, 1_000);
        assertFalse(ChunkTransferPolicy.acceptsRequest(inFlight, session, 5, 0, 8, 40, 2_499));
        assertFalse(ChunkTransferPolicy.acceptsRequest(inFlight, session, 5, 8, 8, 40, 2_500));
        assertTrue(ChunkTransferPolicy.acceptsRequest(inFlight, session, 5, 0, 8, 40, 2_500));

        ChunkTransferPolicy.State acknowledged = ChunkTransferPolicy.acknowledge(inFlight, session, 4, 7, 20_000, 2_500).orElseThrow();
        assertFalse(ChunkTransferPolicy.acceptsRequest(acknowledged, session, 5, 8, 8, 40, 5_000));
        assertTrue(ChunkTransferPolicy.acceptsRequest(acknowledged, session, 5, 8, 8, 40, 10_501));
    }

    @Test void retryGateStaysClosedUntilTheDeferredDeadline() {
        RetryGate gate = new RetryGate();
        assertTrue(gate.ready(100));
        gate.deferUntil(30_000);
        assertFalse(gate.ready(29_999));
        assertTrue(gate.ready(30_000));
        gate.clear();
        assertTrue(gate.ready(0));
    }

    @Test void transferCannotRunAheadOfTheAuthoritativePlaybackWindow() {
        assertTrue(ChunkTransferPolicy.withinPlaybackLead(17_000, 5_000, 12_000));
        assertFalse(ChunkTransferPolicy.withinPlaybackLead(17_001, 5_000, 12_000));
        assertFalse(ChunkTransferPolicy.withinPlaybackLead(-1, 5_000, 12_000));
    }
}
