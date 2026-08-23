package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;


import stonytark.jammarr.core.server.PlexException;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class StationSelectionTest {
    @Test void validatesModeSpecificSeedRules() throws Exception {
        assertDoesNotThrow(() -> StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure",
                List.of(seed(JammarrPayloads.ItemKind.TRACK, "1"), seed(JammarrPayloads.ItemKind.TRACK, "2")), 1)));
        assertThrows(PlexException.class, () -> StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_MIX, "Mix",
                List.of(seed(JammarrPayloads.ItemKind.TRACK, "1"), seed(JammarrPayloads.ItemKind.ARTIST, "2")), 1)));
        assertThrows(PlexException.class, () -> StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure",
                List.of(seed(JammarrPayloads.ItemKind.TRACK, "1")), 1)));
    }

    private static JammarrPayloads.StationSeed seed(JammarrPayloads.ItemKind kind, String key) { return new JammarrPayloads.StationSeed(kind, key, key, ""); }
}
