package stonytark.jammarr.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.network.JammarrPayloads;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SavedDataFixtureTest {
    @Test void migratesSchemasOneThroughThreeAndRoundTripsCanonicalSchemaFour() throws Exception {
        assertSchemaOne(roundTrip("schema-1.snbt"));
        assertSchemaTwo(roundTrip("schema-2.snbt"));
        assertSchemaThree(roundTrip("schema-3.snbt"));
    }

    @Test void clearAllRemovesPlaybackHistoryWithTheRestOfGlobalState() throws Exception {
        JammarrSavedData state = roundTrip("schema-3.snbt");

        state.clearAll();

        assertTrue(state.queue().isEmpty());
        assertTrue(state.history().isEmpty());
        assertNull(state.current());
        assertEquals(JammarrPayloads.PlaybackOrigin.NONE, state.currentOrigin());
        assertFalse(state.autoplayEnabled());
        assertEquals(0L, state.checkpointMs());
        assertFalse(state.paused());
    }

    private static JammarrSavedData roundTrip(String fixture) throws Exception {
        JammarrSavedData migrated = JammarrSavedData.load(read(fixture));
        CompoundTag canonical = migrated.saveTag();
        assertEquals(4, canonical.getIntOr("schemaVersion", 0), fixture);
        JammarrSavedData restored = JammarrSavedData.load(canonical);
        assertEquivalent(migrated, restored, fixture);
        return restored;
    }

    private static CompoundTag read(String fixture) throws Exception {
        InputStream stream = SavedDataFixtureTest.class.getResourceAsStream("/saved-data/" + fixture);
        assertNotNull(stream, fixture);
        try { return TagParser.parseCompoundFully(new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        finally { stream.close(); }
    }

    private static void assertSchemaOne(JammarrSavedData state) {
        assertEquals("v1-current", state.current().key()); assertEquals(JammarrPayloads.PlaybackOrigin.MANUAL, state.currentOrigin());
        assertEquals("Manual request", state.currentSourceName()); assertEquals("v1-next", state.queue().getFirst().key());
        assertEquals(111, state.checkpointMs()); assertTrue(state.paused());
    }

    private static void assertSchemaTwo(JammarrSavedData state) {
        assertEquals("v2-current", state.current().key()); assertEquals(JammarrPayloads.PlaybackOrigin.STATION, state.currentOrigin());
        assertEquals("Station", state.currentSourceName()); assertEquals("v2-next", state.queue().getFirst().key());
        assertEquals(222, state.checkpointMs()); assertTrue(state.paused());
    }

    private static void assertSchemaThree(JammarrSavedData state) {
        assertEquals("v3-current", state.current().key()); assertEquals(JammarrPayloads.PlaybackOrigin.ADVENTURE, state.currentOrigin());
        assertEquals("Sonic Adventure: Fixture Route", state.currentSourceName());
        assertEquals(JammarrPayloads.StationType.SONIC_ADVENTURE, state.station().type()); assertEquals(17, state.station().generation());
        assertEquals(2, state.station().seeds().size()); assertTrue(state.autoplayEnabled());
        assertEquals("history-a", state.history().getFirst().key()); assertFalse(state.paused());
    }

    private static void assertEquivalent(JammarrSavedData expected, JammarrSavedData actual, String fixture) {
        assertEquals(expected.queue(), actual.queue(), fixture + " queue"); assertEquals(expected.history(), actual.history(), fixture + " history");
        assertEquals(expected.current(), actual.current(), fixture + " current"); assertEquals(expected.currentOrigin(), actual.currentOrigin(), fixture + " origin");
        assertEquals(expected.currentSourceName(), actual.currentSourceName(), fixture + " source");
        assertEquals(expected.station().type(), actual.station().type(), fixture + " station type");
        assertEquals(expected.station().name(), actual.station().name(), fixture + " station name");
        assertEquals(expected.station().generation(), actual.station().generation(), fixture + " generation");
        assertEquals(expected.station().seeds(), actual.station().seeds(), fixture + " seeds");
        assertEquals(expected.autoplayEnabled(), actual.autoplayEnabled(), fixture + " autoplay");
        assertEquals(expected.checkpointMs(), actual.checkpointMs(), fixture + " checkpoint"); assertEquals(expected.paused(), actual.paused(), fixture + " paused");
    }
}
