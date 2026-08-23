package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JammarrScreenTest {
    @Test void queueRequestDoesNotInheritThePreviousSearchQuery() {
        assertEquals("", JammarrScreen.browseQuery(JammarrPayloads.BrowseKind.QUEUE, "previous search"));
    }

    @Test void textSearchRetainsItsTrimmedQuery() {
        assertEquals("search terms", JammarrScreen.browseQuery(JammarrPayloads.BrowseKind.SEARCH, "  search terms  "));
    }
}
