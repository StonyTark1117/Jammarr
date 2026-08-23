package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;


import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.ArrayList;
import java.util.List;

public final class JammarrSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 4;
    // Jammarr owns schema migration; a vanilla DataFixTypes transform must not rewrite this custom payload.
    public static final Factory<JammarrSavedData> FACTORY = new Factory<>(JammarrSavedData::new, JammarrSavedData::load, null);
    private final List<QueueTrack> queue = new ArrayList<>();
    private final List<QueueTrack> history = new ArrayList<>();
    private QueueTrack current;
    private stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin currentOrigin = stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.NONE;
    private String currentSourceName = "";
    private StationDefinition station = StationDefinition.none(0);
    private boolean autoplayEnabled;
    private long checkpointMs;
    private boolean paused;

    public List<QueueTrack> queue() { return queue; }
    public List<QueueTrack> history() { return history; }
    public QueueTrack current() { return current; }
    public stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin currentOrigin() { return currentOrigin; }
    public String currentSourceName() { return currentSourceName; }
    public StationDefinition station() { return station; }
    public boolean autoplayEnabled() { return autoplayEnabled; }
    public long checkpointMs() { return checkpointMs; }
    public boolean paused() { return paused; }
    public void current(QueueTrack track, stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin origin) {
        current(track, origin, origin == stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.MANUAL ? "Manual request"
                : origin == stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.ADVENTURE ? "Sonic Adventure" : "");
    }
    public void current(QueueTrack track, stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin origin, String sourceName) {
        current = track; currentOrigin = track == null ? stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.NONE : origin;
        currentSourceName = track == null ? "" : sourceName; setDirty();
    }
    public void station(StationDefinition value) { station = value == null ? StationDefinition.none(station.generation() + 1) : value; setDirty(); }
    public void autoplayEnabled(boolean enabled) { autoplayEnabled = enabled; setDirty(); }
    public void remember(QueueTrack track) {
        if (track == null) return;
        history.add(track);
        while (history.size() > StationGenerator.TRACK_HISTORY_LIMIT) history.removeFirst();
        setDirty();
    }
    public void clearAll() {
        queue.clear(); current = null;
        currentOrigin = stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.NONE; currentSourceName = "";
        station = StationDefinition.none(station.generation() + 1); autoplayEnabled = false;
        checkpointMs = 0; paused = false; setDirty();
    }
    public void update(long checkpointMs, boolean paused) { this.checkpointMs = Math.max(0, checkpointMs); this.paused = paused; setDirty(); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag(); queue.forEach(track -> list.add(QueueTrackCodec.save(track)));
        tag.put("queue", list);
        if (current != null) tag.put("current", QueueTrackCodec.save(current));
        tag.putString("currentOrigin", currentOrigin.name()); tag.putString("currentSourceName", currentSourceName);
        tag.put("station", station.save()); tag.putBoolean("autoplayEnabled", autoplayEnabled);
        ListTag historyTags = new ListTag(); history.forEach(track -> historyTags.add(QueueTrackCodec.save(track))); tag.put("history", historyTags);
        tag.putLong("checkpointMs", checkpointMs); tag.putBoolean("paused", paused); return tag;
    }

    public static JammarrSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        JammarrSavedData data = new JammarrSavedData();
        ListTag list = tag.getList("queue", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) data.queue.add(QueueTrackCodec.load(list.getCompound(i)));
        if (tag.getInt("schemaVersion") < 2) {
            if (!data.queue.isEmpty()) data.current = data.queue.removeFirst();
            data.currentOrigin = data.current == null ? stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.NONE
                    : stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.MANUAL;
            data.currentSourceName = data.current == null ? "" : "Manual request";
        } else {
            if (tag.contains("current", Tag.TAG_COMPOUND)) data.current = QueueTrackCodec.load(tag.getCompound("current"));
            try { data.currentOrigin = stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.valueOf(tag.getString("currentOrigin")); }
            catch (IllegalArgumentException ignored) { data.currentOrigin = data.current == null ? stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.NONE : stonytark.jammarr.network.JammarrPayloads.PlaybackOrigin.MANUAL; }
            data.currentSourceName = tag.getString("currentSourceName");
            if (data.current != null && data.currentSourceName.isBlank()) data.currentSourceName = switch (data.currentOrigin) {
                case MANUAL -> "Manual request"; case ADVENTURE -> "Sonic Adventure"; case STATION -> "Station"; case NONE -> "";
            };
            if (tag.contains("station", Tag.TAG_COMPOUND)) data.station = StationDefinition.load(tag.getCompound("station"));
            data.autoplayEnabled = tag.getBoolean("autoplayEnabled");
            ListTag historyTags = tag.getList("history", Tag.TAG_COMPOUND);
            for (int i = Math.max(0, historyTags.size() - StationGenerator.TRACK_HISTORY_LIMIT); i < historyTags.size(); i++) data.history.add(QueueTrackCodec.load(historyTags.getCompound(i)));
        }
        data.checkpointMs = tag.getLong("checkpointMs"); data.paused = tag.getBoolean("paused"); return data;
    }
}
