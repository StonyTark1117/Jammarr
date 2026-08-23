package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;


import stonytark.jammarr.core.server.SlidingWindowRateLimiter;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.List;
import stonytark.jammarr.network.JammarrPayloads;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceRateLimitTest {
    @Test void roundTripsQueueOrderAndPlaybackCheckpoint() {
        JammarrSavedData original = new JammarrSavedData();
        original.queue().add(new QueueTrack("2", "Second", "Artist", "Album", 2_000));
        original.queue().add(new QueueTrack("1", "First", "Artist", "Album", 1_000));
        original.update(1_234, true);
        CompoundTag tag = original.save(new CompoundTag(), null);
        assertEquals(JammarrSavedData.SCHEMA_VERSION, tag.getInt("schemaVersion"));
        JammarrSavedData restored = JammarrSavedData.load(tag, null);
        assertEquals(2, restored.queue().size()); assertEquals("2", restored.queue().getFirst().key());
        assertEquals(1_234, restored.checkpointMs()); assertTrue(restored.paused());
    }
    @Test void limitsEachPlayerIndependentlyPerSecond() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(limiter.allow(first, 2, 1_000)); assertTrue(limiter.allow(first, 2, 1_100)); assertFalse(limiter.allow(first, 2, 1_200));
        assertTrue(limiter.allow(second, 2, 1_200)); assertTrue(limiter.allow(first, 2, 2_000));
    }
    @Test void roundTripsCurrentStationAutoplayAndRecentHistory() {
        JammarrSavedData original = new JammarrSavedData(); QueueTrack current = new QueueTrack("9", "Current", "Artist", "Album", 9_000);
        original.current(current, JammarrPayloads.PlaybackOrigin.ADVENTURE, "Adventure: Desert Run"); original.remember(new QueueTrack("8", "Prior", "Other", "Album", 8_000));
        original.autoplayEnabled(true); original.station(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure",
                List.of(new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, "8", "Prior", "Other"),
                        new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, "9", "Current", "Artist")), 7));
        JammarrSavedData restored = JammarrSavedData.load(original.save(new CompoundTag(), null), null);
        assertEquals("9", restored.current().key()); assertEquals(JammarrPayloads.PlaybackOrigin.ADVENTURE, restored.currentOrigin());
        assertEquals("Adventure: Desert Run", restored.currentSourceName());
        assertTrue(restored.autoplayEnabled()); assertEquals(7, restored.station().generation()); assertEquals(2, restored.station().seeds().size());
        assertEquals(List.of("8"), restored.history().stream().map(QueueTrack::key).toList());
    }

    @Test void migratesLegacyQueueFirstItemToCurrent() {
        CompoundTag legacy = new CompoundTag(); net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(QueueTrackCodec.save(new QueueTrack("1", "Current", "Artist", "Album", 1_000)));
        list.add(QueueTrackCodec.save(new QueueTrack("2", "Next", "Artist", "Album", 1_000))); legacy.put("queue", list);
        JammarrSavedData restored = JammarrSavedData.load(legacy, null);
        assertEquals("1", restored.current().key()); assertEquals(JammarrPayloads.PlaybackOrigin.MANUAL, restored.currentOrigin());
        assertEquals(List.of("2"), restored.queue().stream().map(QueueTrack::key).toList());
    }

    @Test void migratesSchemaTwoCurrentTrackAndSynthesizesItsSourceName() {
        CompoundTag schemaTwo = new CompoundTag(); schemaTwo.putInt("schemaVersion", 2);
        schemaTwo.put("current", QueueTrackCodec.save(new QueueTrack("1", "Current", "Artist", "Album", 1_000)));
        schemaTwo.putString("currentOrigin", JammarrPayloads.PlaybackOrigin.STATION.name());
        schemaTwo.putLong("checkpointMs", 500); schemaTwo.putBoolean("paused", true);
        JammarrSavedData restored = JammarrSavedData.load(schemaTwo, null);
        assertEquals("1", restored.current().key()); assertEquals(JammarrPayloads.PlaybackOrigin.STATION, restored.currentOrigin());
        assertEquals("Station", restored.currentSourceName()); assertEquals(500, restored.checkpointMs()); assertTrue(restored.paused());
    }

    @Test void migratesSchemaThreeStationAutoplayAndHistoryToCanonicalSchemaFour() {
        JammarrSavedData original = new JammarrSavedData();
        original.station(new StationDefinition(JammarrPayloads.StationType.TRACK_RADIO, "Track Radio",
                List.of(new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, "seed", "Seed", "Artist")), 3));
        original.autoplayEnabled(true); original.remember(new QueueTrack("history", "History", "Artist", "Album", 2_000));
        CompoundTag schemaThree = original.save(new CompoundTag(), null); schemaThree.putInt("schemaVersion", 3);
        JammarrSavedData restored = JammarrSavedData.load(schemaThree, null);
        CompoundTag canonical = restored.save(new CompoundTag(), null);
        assertEquals(4, canonical.getInt("schemaVersion")); assertTrue(restored.autoplayEnabled());
        assertEquals(JammarrPayloads.StationType.TRACK_RADIO, restored.station().type());
        assertEquals(List.of("history"), restored.history().stream().map(QueueTrack::key).toList());
    }

    @Test void globalClearRemovesPlaybackAndActiveSourceButRetainsRepeatHistory() {
        JammarrSavedData data = new JammarrSavedData();
        data.current(new QueueTrack("current", "Current", "Artist", "Album", 1_000), JammarrPayloads.PlaybackOrigin.STATION);
        data.queue().add(new QueueTrack("manual", "Manual", "Artist", "Album", 1_000));
        data.remember(new QueueTrack("history", "History", "Artist", "Album", 1_000));
        data.autoplayEnabled(true);
        data.station(new StationDefinition(JammarrPayloads.StationType.LIBRARY_SHUFFLE, "Shuffle", List.of(), 5));
        data.clearAll();
        assertNull(data.current()); assertTrue(data.queue().isEmpty()); assertFalse(data.autoplayEnabled());
        assertEquals(JammarrPayloads.StationType.NONE, data.station().type());
        assertEquals(List.of("history"), data.history().stream().map(QueueTrack::key).toList());
    }
}
