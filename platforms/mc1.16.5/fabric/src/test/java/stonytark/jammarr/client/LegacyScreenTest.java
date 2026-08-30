package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyScreenTest {
    @Test void everyMainMenuControlHasHoverHelp() {
        for (int id = 10; id <= 17; id++) assertNotNull(LegacyUiTooltips.tooltip(id), "tab " + id);
        for (int id = 50; id <= 63; id++) assertNotNull(LegacyUiTooltips.tooltip(id), "control " + id);
        for (int id = 70; id <= 73; id++) assertNotNull(LegacyUiTooltips.tooltip(id), "adventure control " + id);
        for (int id : new int[] {100, 200, 300, 400, 500, 600, 700, 800}) {
            assertNotNull(LegacyUiTooltips.tooltip(id), "row action " + id);
        }
        assertNull(LegacyUiTooltips.tooltip(999));
    }
}
