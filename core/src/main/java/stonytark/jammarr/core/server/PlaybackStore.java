package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.StatePackets;

import java.util.List;

/** Canonical schema-4 state contract implemented by each Minecraft saved-data adapter. */
public interface PlaybackStore {
    List<QueueTrack> queue();
    List<QueueTrack> history();
    QueueTrack current();
    StatePackets.PlaybackOrigin currentOrigin();
    String currentSourceName();
    StationModels.StationDefinition station();
    boolean autoplayEnabled();
    long checkpointMs();
    boolean paused();
    void current(QueueTrack track, StatePackets.PlaybackOrigin origin, String sourceName);
    void station(StationModels.StationDefinition value);
    void autoplayEnabled(boolean enabled);
    void remember(QueueTrack track);
    void update(long checkpointMs, boolean paused);
    void clearAll();
    void markDirty();
}
