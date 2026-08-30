package stonytark.jammarr.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.saveddata.SavedData;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.server.StationGenerator;
import stonytark.jammarr.core.server.PlaybackStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Schema-4 world state stored through Minecraft 1.16.5 SavedData. */
public final class LegacySavedData extends SavedData implements PlaybackStore {
    public static final String DATA_NAME = "jammarr_global_queue";
    public static final int SCHEMA_VERSION = 4;
    private static final int MAX_QUEUE = 500;
    private static final long MAX_DURATION_MS = 3L * 60L * 60L * 1_000L;

    private final List<QueueTrack> queue = new ArrayList<QueueTrack>();
    private final List<QueueTrack> history = new ArrayList<QueueTrack>();
    private QueueTrack current;
    private StatePackets.PlaybackOrigin currentOrigin = StatePackets.PlaybackOrigin.NONE;
    private String currentSourceName = "";
    private StationModels.StationDefinition station = StationModels.StationDefinition.none(0L);
    private boolean autoplayEnabled;
    private long checkpointMs;
    private boolean paused;

    public LegacySavedData() { this(DATA_NAME); }
    public LegacySavedData(String name) { super(name); }

    public static LegacySavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(LegacySavedData::new, DATA_NAME);
    }

    public List<QueueTrack> queue() { return queue; }
    public List<QueueTrack> history() { return history; }
    public QueueTrack current() { return current; }
    public StatePackets.PlaybackOrigin currentOrigin() { return currentOrigin; }
    public String currentSourceName() { return currentSourceName; }
    public StationModels.StationDefinition station() { return station; }
    public boolean autoplayEnabled() { return autoplayEnabled; }
    public long checkpointMs() { return checkpointMs; }
    public boolean paused() { return paused; }

    /**
     * PlaybackStore deliberately avoids SavedData.setDirty's MCP name:
     * that inherited method is reobfuscated in the production legacy JAR.
     */
    @Override public void markChanged() { super.setDirty(); }

    public void current(QueueTrack track, StatePackets.PlaybackOrigin origin, String sourceName) {
        current = track;
        currentOrigin = track == null || origin == null ? StatePackets.PlaybackOrigin.NONE : origin;
        currentSourceName = track == null ? "" : bounded(sourceName, 256);
        setDirty();
    }

    public void station(StationModels.StationDefinition value) {
        station = value == null ? StationModels.StationDefinition.none(station.generation() + 1L) : value;
        setDirty();
    }

    public void autoplayEnabled(boolean enabled) { autoplayEnabled = enabled; setDirty(); }

    public void remember(QueueTrack track) {
        if (track == null) return;
        history.add(track);
        while (history.size() > StationGenerator.TRACK_HISTORY_LIMIT) history.remove(0);
        setDirty();
    }

    public void update(long checkpoint, boolean isPaused) {
        checkpointMs = Math.max(0L, checkpoint);
        paused = isPaused;
        setDirty();
    }

    public void clearAll() {
        queue.clear();
        current = null;
        currentOrigin = StatePackets.PlaybackOrigin.NONE;
        currentSourceName = "";
        station = StationModels.StationDefinition.none(station.generation() + 1L);
        autoplayEnabled = false;
        checkpointMs = 0L;
        paused = false;
        setDirty();
    }

    @Override
    public void load(CompoundTag tag) {
        queue.clear();
        history.clear();
        current = null;
        currentOrigin = StatePackets.PlaybackOrigin.NONE;
        currentSourceName = "";
        station = StationModels.StationDefinition.none(0L);
        autoplayEnabled = false;

        int schema = tag.getInt("schemaVersion");
        ListTag queueTags = tag.getList("queue", 10);
        for (int index = 0; index < Math.min(MAX_QUEUE, queueTags.size()); index++) {
            QueueTrack track = readTrack(queueTags.getCompound(index));
            if (playable(track)) queue.add(track);
        }
        if (schema < 2) {
            if (!queue.isEmpty()) current = queue.remove(0);
            currentOrigin = current == null ? StatePackets.PlaybackOrigin.NONE : StatePackets.PlaybackOrigin.MANUAL;
            currentSourceName = current == null ? "" : "Manual request";
        } else {
            if (tag.contains("current", 10)) {
                QueueTrack value = readTrack(tag.getCompound("current"));
                if (playable(value)) current = value;
            }
            currentOrigin = enumValue(StatePackets.PlaybackOrigin.class, tag.getString("currentOrigin"),
                    current == null ? StatePackets.PlaybackOrigin.NONE : StatePackets.PlaybackOrigin.MANUAL);
            currentSourceName = bounded(tag.getString("currentSourceName"), 256);
            if (current != null && currentSourceName.isEmpty()) currentSourceName = defaultSource(currentOrigin);
            if (tag.contains("station", 10)) station = readStation(tag.getCompound("station"));
            autoplayEnabled = tag.getBoolean("autoplayEnabled");
            ListTag historyTags = tag.getList("history", 10);
            int first = Math.max(0, historyTags.size() - StationGenerator.TRACK_HISTORY_LIMIT);
            for (int index = first; index < historyTags.size(); index++) {
                QueueTrack track = readTrack(historyTags.getCompound(index));
                if (playable(track)) history.add(track);
            }
        }
        checkpointMs = Math.max(0L, tag.getLong("checkpointMs"));
        paused = tag.getBoolean("paused");
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag queueTags = new ListTag();
        for (int index = 0; index < Math.min(MAX_QUEUE, queue.size()); index++) queueTags.add(writeTrack(queue.get(index)));
        tag.put("queue", queueTags);
        if (current != null) tag.put("current", writeTrack(current));
        tag.putString("currentOrigin", currentOrigin.name());
        tag.putString("currentSourceName", bounded(currentSourceName, 256));
        tag.put("station", writeStation(station));
        tag.putBoolean("autoplayEnabled", autoplayEnabled);
        ListTag historyTags = new ListTag();
        int first = Math.max(0, history.size() - StationGenerator.TRACK_HISTORY_LIMIT);
        for (int index = first; index < history.size(); index++) historyTags.add(writeTrack(history.get(index)));
        tag.put("history", historyTags);
        tag.putLong("checkpointMs", Math.max(0L, checkpointMs));
        tag.putBoolean("paused", paused);
        return tag;
    }

    private static CompoundTag writeTrack(QueueTrack track) {
        CompoundTag tag = new CompoundTag();
        tag.putString("key", bounded(track.key(), 256));
        tag.putString("title", bounded(track.title(), 256));
        tag.putString("artist", bounded(track.artist(), 256));
        tag.putString("album", bounded(track.album(), 256));
        tag.putLong("duration", Math.min(MAX_DURATION_MS, Math.max(0L, track.durationMs())));
        return tag;
    }

    private static QueueTrack readTrack(CompoundTag tag) {
        return new QueueTrack(bounded(tag.getString("key"), 256), bounded(tag.getString("title"), 256),
                bounded(tag.getString("artist"), 256), bounded(tag.getString("album"), 256),
                Math.min(MAX_DURATION_MS, Math.max(0L, tag.getLong("duration"))));
    }

    private static boolean playable(QueueTrack track) { return track != null && !track.key().isEmpty(); }

    private static CompoundTag writeStation(StationModels.StationDefinition station) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", station.type().name());
        tag.putString("name", bounded(station.name(), 256));
        tag.putLong("generation", Math.max(0L, station.generation()));
        ListTag seeds = new ListTag();
        for (StationModels.StationSeed seed : station.seeds()) {
            CompoundTag value = new CompoundTag();
            value.putString("kind", seed.kind().name());
            value.putString("key", bounded(seed.key(), 256));
            value.putString("title", bounded(seed.title(), 256));
            value.putString("subtitle", bounded(seed.subtitle(), 256));
            seeds.add(value);
        }
        tag.put("seeds", seeds);
        return tag;
    }

    private static StationModels.StationDefinition readStation(CompoundTag tag) {
        StationModels.StationType type = enumValue(StationModels.StationType.class, tag.getString("type"), StationModels.StationType.NONE);
        List<StationModels.StationSeed> seeds = new ArrayList<StationModels.StationSeed>();
        ListTag values = tag.getList("seeds", 10);
        for (int index = 0; index < Math.min(5, values.size()); index++) {
            CompoundTag value = values.getCompound(index);
            StationModels.ItemKind kind = enumValue(StationModels.ItemKind.class, value.getString("kind"), null);
            String key = bounded(value.getString("key"), 256);
            if (kind != null && !key.isEmpty()) {
                seeds.add(new StationModels.StationSeed(kind, key, bounded(value.getString("title"), 256),
                        bounded(value.getString("subtitle"), 256)));
            }
        }
        return new StationModels.StationDefinition(type, bounded(tag.getString("name"), 256), seeds,
                Math.max(0L, tag.getLong("generation")));
    }

    private static String defaultSource(StatePackets.PlaybackOrigin origin) {
        if (origin == StatePackets.PlaybackOrigin.MANUAL) return "Manual request";
        if (origin == StatePackets.PlaybackOrigin.ADVENTURE) return "Sonic Adventure";
        if (origin == StatePackets.PlaybackOrigin.STATION) return "Station";
        return "";
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try { return Enum.valueOf(type, value == null ? "" : value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
