package stonytark.jammarr.server;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.ArrayList;
import java.util.List;

public final class JammarrSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 4;
    private static final Codec<JammarrSavedData> CODEC = CompoundTag.CODEC.xmap(JammarrSavedData::load, JammarrSavedData::saveTag);
    // Command storage is the closest vanilla opaque-data fix type; Jammarr still owns its inner schema migration.
    public static final SavedDataType<JammarrSavedData> TYPE = new SavedDataType<>(
            "jammarr_global_queue",
            JammarrSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    public static JammarrSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private final List<QueueTrack> queue = new ArrayList<>();
    private final List<QueueTrack> history = new ArrayList<>();
    private QueueTrack current;
    private JammarrPayloads.PlaybackOrigin currentOrigin = JammarrPayloads.PlaybackOrigin.NONE;
    private String currentSourceName = "";
    private StationDefinition station = StationDefinition.none(0);
    private boolean autoplayEnabled;
    private long checkpointMs;
    private boolean paused;

    public List<QueueTrack> queue() { return queue; }
    public List<QueueTrack> history() { return history; }
    public QueueTrack current() { return current; }
    public JammarrPayloads.PlaybackOrigin currentOrigin() { return currentOrigin; }
    public String currentSourceName() { return currentSourceName; }
    public StationDefinition station() { return station; }
    public boolean autoplayEnabled() { return autoplayEnabled; }
    public long checkpointMs() { return checkpointMs; }
    public boolean paused() { return paused; }

    public void current(QueueTrack track, JammarrPayloads.PlaybackOrigin origin) {
        current(track, origin, origin == JammarrPayloads.PlaybackOrigin.MANUAL ? "Manual request"
                : origin == JammarrPayloads.PlaybackOrigin.ADVENTURE ? "Sonic Adventure" : "");
    }

    public void current(QueueTrack track, JammarrPayloads.PlaybackOrigin origin, String sourceName) {
        current = track;
        currentOrigin = track == null ? JammarrPayloads.PlaybackOrigin.NONE : origin;
        currentSourceName = track == null ? "" : sourceName;
        setDirty();
    }

    public void station(StationDefinition value) {
        station = value == null ? StationDefinition.none(station.generation() + 1) : value;
        setDirty();
    }

    public void autoplayEnabled(boolean enabled) { autoplayEnabled = enabled; setDirty(); }

    public void remember(QueueTrack track) {
        if (track == null) return;
        history.add(track);
        while (history.size() > StationGenerator.TRACK_HISTORY_LIMIT) history.remove(0);
        setDirty();
    }

    public void clearAll() {
        queue.clear(); history.clear(); current = null;
        currentOrigin = JammarrPayloads.PlaybackOrigin.NONE; currentSourceName = "";
        station = StationDefinition.none(station.generation() + 1); autoplayEnabled = false;
        checkpointMs = 0; paused = false; setDirty();
    }

    public void update(long checkpointMs, boolean paused) {
        this.checkpointMs = Math.max(0, checkpointMs); this.paused = paused; setDirty();
    }

    CompoundTag saveTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag(); queue.forEach(track -> list.add(QueueTrackCodec.save(track))); tag.put("queue", list);
        if (current != null) tag.put("current", QueueTrackCodec.save(current));
        tag.putString("currentOrigin", currentOrigin.name()); tag.putString("currentSourceName", currentSourceName);
        tag.put("station", station.save()); tag.putBoolean("autoplayEnabled", autoplayEnabled);
        ListTag historyTags = new ListTag(); history.forEach(track -> historyTags.add(QueueTrackCodec.save(track))); tag.put("history", historyTags);
        tag.putLong("checkpointMs", checkpointMs); tag.putBoolean("paused", paused);
        return tag;
    }

    public static JammarrSavedData load(CompoundTag tag) {
        JammarrSavedData data = new JammarrSavedData();
        ListTag list = tag.getListOrEmpty("queue");
        for (int i = 0; i < list.size(); i++) data.queue.add(QueueTrackCodec.load(list.getCompoundOrEmpty(i)));
        if (tag.getIntOr("schemaVersion", 1) < 2) {
            if (!data.queue.isEmpty()) data.current = data.queue.remove(0);
            data.currentOrigin = data.current == null ? JammarrPayloads.PlaybackOrigin.NONE : JammarrPayloads.PlaybackOrigin.MANUAL;
            data.currentSourceName = data.current == null ? "" : "Manual request";
        } else {
            if (tag.contains("current")) data.current = QueueTrackCodec.load(tag.getCompoundOrEmpty("current"));
            try { data.currentOrigin = JammarrPayloads.PlaybackOrigin.valueOf(tag.getStringOr("currentOrigin", "")); }
            catch (IllegalArgumentException ignored) { data.currentOrigin = data.current == null ? JammarrPayloads.PlaybackOrigin.NONE : JammarrPayloads.PlaybackOrigin.MANUAL; }
            data.currentSourceName = tag.getStringOr("currentSourceName", "");
            if (data.current != null && data.currentSourceName.isBlank()) data.currentSourceName = switch (data.currentOrigin) {
                case MANUAL -> "Manual request"; case ADVENTURE -> "Sonic Adventure"; case STATION -> "Station"; case NONE -> "";
            };
            if (tag.contains("station")) data.station = StationDefinition.load(tag.getCompoundOrEmpty("station"));
            data.autoplayEnabled = tag.getBooleanOr("autoplayEnabled", false);
            ListTag historyTags = tag.getListOrEmpty("history");
            for (int i = Math.max(0, historyTags.size() - StationGenerator.TRACK_HISTORY_LIMIT); i < historyTags.size(); i++) {
                data.history.add(QueueTrackCodec.load(historyTags.getCompoundOrEmpty(i)));
            }
        }
        data.checkpointMs = tag.getLongOr("checkpointMs", 0L);
        data.paused = tag.getBooleanOr("paused", false);
        return data;
    }
}
