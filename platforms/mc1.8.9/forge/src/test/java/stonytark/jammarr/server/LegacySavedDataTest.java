package stonytark.jammarr.server;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.StatePackets;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySavedDataTest {
    @Test
    void migratesSharedSchemasOneThroughThreeAndRoundTripsSchemaFour() throws Exception {
        assertSchemaOne(roundTrip("schema-1.snbt"));
        assertSchemaTwo(roundTrip("schema-2.snbt"));
        assertSchemaThree(roundTrip("schema-3.snbt"));
    }

    @Test
    void roundTripsSchema4AdventureAutoplayAndHistory() {
        LegacySavedData source = new LegacySavedData();
        source.queue().add(track("queued"));
        source.current(track("current"), StatePackets.PlaybackOrigin.ADVENTURE, "Sonic Adventure");
        source.station(new StationModels.StationDefinition(StationModels.StationType.SONIC_ADVENTURE, "A to B",
                Arrays.asList(seed("start"), seed("end")), 17L));
        source.autoplayEnabled(true);
        source.remember(track("history"));
        source.update(1_234L, true);

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);
        LegacySavedData restored = new LegacySavedData();
        restored.readFromNBT(tag);

        assertEquals(LegacySavedData.SCHEMA_VERSION, tag.getInteger("schemaVersion"));
        assertEquals("queued", restored.queue().get(0).key());
        assertEquals("current", restored.current().key());
        assertEquals(StatePackets.PlaybackOrigin.ADVENTURE, restored.currentOrigin());
        assertEquals("Sonic Adventure", restored.currentSourceName());
        assertEquals(StationModels.StationType.SONIC_ADVENTURE, restored.station().type());
        assertEquals(2, restored.station().seeds().size());
        assertEquals(17L, restored.station().generation());
        assertTrue(restored.autoplayEnabled());
        assertEquals("history", restored.history().get(0).key());
        assertEquals(1_234L, restored.checkpointMs());
        assertTrue(restored.paused());
    }

    @Test
    void migratesSchema1QueueHeadToCurrentTrack() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schemaVersion", 1);
        NBTTagList queue = new NBTTagList();
        queue.appendTag(trackTag("first"));
        queue.appendTag(trackTag("second"));
        tag.setTag("queue", queue);

        LegacySavedData restored = new LegacySavedData();
        restored.readFromNBT(tag);

        assertEquals("first", restored.current().key());
        assertEquals(StatePackets.PlaybackOrigin.MANUAL, restored.currentOrigin());
        assertEquals("Manual request", restored.currentSourceName());
        assertEquals(1, restored.queue().size());
        assertEquals("second", restored.queue().get(0).key());
        assertFalse(restored.autoplayEnabled());
    }

    @Test
    void boundsPersistedQueueSeedsDurationsAndStrings() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schemaVersion", 4);
        NBTTagList queue = new NBTTagList();
        for (int index = 0; index < 550; index++) queue.appendTag(trackTag("track-" + index));
        tag.setTag("queue", queue);
        NBTTagCompound station = new NBTTagCompound();
        station.setString("type", "SONIC_MIX");
        station.setString("name", repeat('x', 300));
        NBTTagList seeds = new NBTTagList();
        for (int index = 0; index < 9; index++) {
            NBTTagCompound seed = new NBTTagCompound();
            seed.setString("kind", "TRACK");
            seed.setString("key", "seed-" + index);
            seeds.appendTag(seed);
        }
        station.setTag("seeds", seeds);
        tag.setTag("station", station);

        LegacySavedData restored = new LegacySavedData();
        restored.readFromNBT(tag);
        assertEquals(500, restored.queue().size());
        assertEquals(5, restored.station().seeds().size());
        assertEquals(256, restored.station().name().length());
    }

    private static LegacySavedData roundTrip(String fixture) throws Exception {
        LegacySavedData migrated = new LegacySavedData();
        migrated.readFromNBT(net.minecraft.nbt.JsonToNBT.getTagFromJson(readFixture(fixture)));
        NBTTagCompound canonical = new NBTTagCompound();
        migrated.writeToNBT(canonical);
        assertEquals(4, canonical.getInteger("schemaVersion"), fixture);
        LegacySavedData restored = new LegacySavedData();
        restored.readFromNBT(canonical);
        assertEquivalent(migrated, restored, fixture);
        return restored;
    }

    private static String readFixture(String fixture) throws Exception {
        InputStream stream = LegacySavedDataTest.class.getResourceAsStream("/saved-data/" + fixture);
        assertNotNull(stream, fixture);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count = stream.read(buffer); count >= 0; count = stream.read(buffer)) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally { stream.close(); }
    }

    private static void assertSchemaOne(LegacySavedData state) {
        assertEquals("v1-current", state.current().key());
        assertEquals(StatePackets.PlaybackOrigin.MANUAL, state.currentOrigin());
        assertEquals("Manual request", state.currentSourceName());
        assertEquals("v1-next", state.queue().get(0).key());
        assertEquals(111, state.checkpointMs());
        assertTrue(state.paused());
    }

    private static void assertSchemaTwo(LegacySavedData state) {
        assertEquals("v2-current", state.current().key());
        assertEquals(StatePackets.PlaybackOrigin.STATION, state.currentOrigin());
        assertEquals("Station", state.currentSourceName());
        assertEquals("v2-next", state.queue().get(0).key());
        assertEquals(222, state.checkpointMs());
        assertTrue(state.paused());
    }

    private static void assertSchemaThree(LegacySavedData state) {
        assertEquals("v3-current", state.current().key());
        assertEquals(StatePackets.PlaybackOrigin.ADVENTURE, state.currentOrigin());
        assertEquals("Sonic Adventure: Fixture Route", state.currentSourceName());
        assertEquals(StationModels.StationType.SONIC_ADVENTURE, state.station().type());
        assertEquals(17, state.station().generation());
        assertEquals(2, state.station().seeds().size());
        assertTrue(state.autoplayEnabled());
        assertEquals("history-a", state.history().get(0).key());
        assertFalse(state.paused());
    }

    private static void assertEquivalent(LegacySavedData expected, LegacySavedData actual, String fixture) {
        assertEquals(expected.queue(), actual.queue(), fixture + " queue");
        assertEquals(expected.history(), actual.history(), fixture + " history");
        assertEquals(expected.current(), actual.current(), fixture + " current");
        assertEquals(expected.currentOrigin(), actual.currentOrigin(), fixture + " origin");
        assertEquals(expected.currentSourceName(), actual.currentSourceName(), fixture + " source");
        assertEquals(expected.station().type(), actual.station().type(), fixture + " station type");
        assertEquals(expected.station().name(), actual.station().name(), fixture + " station name");
        assertEquals(expected.station().generation(), actual.station().generation(), fixture + " generation");
        assertEquals(expected.station().seeds().size(), actual.station().seeds().size(), fixture + " seeds");
        assertEquals(expected.autoplayEnabled(), actual.autoplayEnabled(), fixture + " autoplay");
        assertEquals(expected.checkpointMs(), actual.checkpointMs(), fixture + " checkpoint");
        assertEquals(expected.paused(), actual.paused(), fixture + " paused");
    }

    private static QueueTrack track(String key) { return new QueueTrack(key, key, "artist", "album", 5_000L); }
    private static StationModels.StationSeed seed(String key) {
        return new StationModels.StationSeed(StationModels.ItemKind.TRACK, key, key, "artist");
    }
    private static NBTTagCompound trackTag(String key) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("key", key);
        tag.setString("title", key);
        tag.setString("artist", "artist");
        tag.setString("album", "album");
        tag.setLong("duration", Long.MAX_VALUE);
        return tag;
    }
    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }
}
