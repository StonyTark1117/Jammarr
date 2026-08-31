package stonytark.jammarr.core.client;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.protocol.ControlPackets;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingBrowseRequestTest {
    @Test void onlyTheMatchingResponseCompletesTheRequest() {
        PendingBrowseRequest pending = new PendingBrowseRequest();
        pending.begin(ControlPackets.BrowseKind.SEARCH, "Gate", 2, 100L);

        assertFalse(pending.complete(results(ControlPackets.BrowseKind.SEARCH, "Other", 2)));
        assertFalse(pending.complete(results(ControlPackets.BrowseKind.SEARCH, "Gate", 1)));
        assertTrue(pending.active());
        assertTrue(pending.complete(results(ControlPackets.BrowseKind.SEARCH, "Gate", 2)));
        assertFalse(pending.active());
    }

    @Test void failureCancellationAndTimeoutAreTerminalAndReusable() {
        PendingBrowseRequest pending = new PendingBrowseRequest();
        pending.begin(ControlPackets.BrowseKind.ARTISTS, "", 0, 1_000L);
        assertFalse(pending.expire(15_999L));
        assertTrue(pending.expire(16_000L));
        assertFalse(pending.fail());

        pending.begin(ControlPackets.BrowseKind.ALBUMS, "", 0, 20_000L);
        assertTrue(pending.cancel());
        pending.begin(ControlPackets.BrowseKind.PLAYLISTS, "", 0, 30_000L);
        assertTrue(pending.fail());
    }

    private static ControlPackets.BrowseResults results(ControlPackets.BrowseKind kind,
                                                         String query, int page) {
        return new ControlPackets.BrowseResults(kind, query, page, false,
                Collections.emptyList());
    }
}
