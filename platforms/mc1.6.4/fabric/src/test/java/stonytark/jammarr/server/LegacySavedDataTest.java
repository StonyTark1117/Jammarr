package stonytark.jammarr.server;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.StatePackets;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        NbtCompound tag = new NbtCompound();
        source.toNbt(tag);
        LegacySavedData restored = new LegacySavedData();
        restored.fromNbt(tag);

        assertEquals(LegacySavedData.SCHEMA_VERSION, tag.getInt("schemaVersion"));
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
        NbtCompound tag = new NbtCompound();
        tag.putInt("schemaVersion", 1);
        NbtList queue = new NbtList();
        queue.addItem(trackTag("first"));
        queue.addItem(trackTag("second"));
        tag.put("queue", queue);

        LegacySavedData restored = new LegacySavedData();
        restored.fromNbt(tag);

        assertEquals("first", restored.current().key());
        assertEquals(StatePackets.PlaybackOrigin.MANUAL, restored.currentOrigin());
        assertEquals("Manual request", restored.currentSourceName());
        assertEquals(1, restored.queue().size());
        assertEquals("second", restored.queue().get(0).key());
        assertFalse(restored.autoplayEnabled());
    }

    @Test
    void boundsPersistedQueueSeedsDurationsAndStrings() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("schemaVersion", 4);
        NbtList queue = new NbtList();
        for (int index = 0; index < 550; index++) queue.addItem(trackTag("track-" + index));
        tag.put("queue", queue);
        NbtCompound station = new NbtCompound();
        station.putString("type", "SONIC_MIX");
        station.putString("name", repeat('x', 300));
        NbtList seeds = new NbtList();
        for (int index = 0; index < 9; index++) {
            NbtCompound seed = new NbtCompound();
            seed.putString("kind", "TRACK");
            seed.putString("key", "seed-" + index);
            seeds.addItem(seed);
        }
        station.put("seeds", seeds);
        tag.put("station", station);

        LegacySavedData restored = new LegacySavedData();
        restored.fromNbt(tag);
        assertEquals(500, restored.queue().size());
        assertEquals(5, restored.station().seeds().size());
        assertEquals(256, restored.station().name().length());
    }

    private static LegacySavedData roundTrip(String fixture) throws Exception {
        LegacySavedData migrated = new LegacySavedData();
        migrated.fromNbt(fixtureTag(fixture));
        NbtCompound canonical = new NbtCompound();
        migrated.toNbt(canonical);
        assertEquals(4, canonical.getInt("schemaVersion"), fixture);
        LegacySavedData restored = new LegacySavedData();
        restored.fromNbt(canonical);
        assertEquivalent(migrated, restored, fixture);
        return restored;
    }

    private static NbtCompound fixtureTag(String fixture) {
        NbtCompound tag = new NbtCompound();
        NbtList queue = new NbtList();
        if ("schema-1.snbt".equals(fixture)) {
            tag.putInt("schemaVersion", 1);
            queue.addItem(trackTag("v1-current"));
            queue.addItem(trackTag("v1-next"));
            tag.putLong("checkpointMs", 111L);
            tag.putBoolean("paused", true);
        } else if ("schema-2.snbt".equals(fixture)) {
            tag.putInt("schemaVersion", 2);
            queue.addItem(trackTag("v2-next"));
            tag.put("current", trackTag("v2-current"));
            tag.putString("currentOrigin", "STATION");
            tag.putLong("checkpointMs", 222L);
            tag.putBoolean("paused", true);
        } else if ("schema-3.snbt".equals(fixture)) {
            tag.putInt("schemaVersion", 3);
            queue.addItem(trackTag("v3-next"));
            tag.put("current", trackTag("v3-current"));
            tag.putString("currentOrigin", "ADVENTURE");
            tag.putString("currentSourceName", "Sonic Adventure: Fixture Route");
            NbtCompound station = new NbtCompound();
            station.putString("type", "SONIC_ADVENTURE");
            station.putString("name", "Fixture Adventure");
            station.putLong("generation", 17L);
            NbtList seeds = new NbtList();
            seeds.addItem(seedTag("seed-a"));
            seeds.addItem(seedTag("seed-b"));
            station.put("seeds", seeds);
            tag.put("station", station);
            tag.putBoolean("autoplayEnabled", true);
            NbtList history = new NbtList();
            history.addItem(trackTag("history-a"));
            tag.put("history", history);
            tag.putLong("checkpointMs", 333L);
        } else throw new IllegalArgumentException(fixture);
        tag.put("queue", queue);
        return tag;
    }

    private static NbtCompound seedTag(String key) {
        NbtCompound tag = new NbtCompound();
        tag.putString("kind", "TRACK");
        tag.putString("key", key);
        tag.putString("title", key);
        tag.putString("subtitle", "artist");
        return tag;
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
    private static NbtCompound trackTag(String key) {
        NbtCompound tag = new NbtCompound();
        tag.putString("key", key);
        tag.putString("title", key);
        tag.putString("artist", "artist");
        tag.putString("album", "album");
        tag.putLong("duration", Long.MAX_VALUE);
        return tag;
    }
    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }
}
