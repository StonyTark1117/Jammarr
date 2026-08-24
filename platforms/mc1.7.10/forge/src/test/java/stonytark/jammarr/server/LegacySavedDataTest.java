package stonytark.jammarr.server;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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
