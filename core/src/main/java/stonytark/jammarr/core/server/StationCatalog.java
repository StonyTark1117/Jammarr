package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.SonicResult;
import stonytark.jammarr.core.model.StationModels.StationSeed;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Bounded Plex operations required by the platform-neutral station engine. */
public interface StationCatalog {
    List<QueueTrack> nativeRadioTracks(StationSeed seed, int limit) throws IOException, InterruptedException;
    boolean hasSonicAnalysis(String key) throws IOException, InterruptedException;
    List<SonicResult> nearest(ItemKind kind, String key, int limit, double maxDistance) throws IOException, InterruptedException;
    List<QueueTrack> nearestTracks(String key, int limit, double maxDistance) throws IOException, InterruptedException;
    List<QueueTrack> sonicPath(String startKey, String endKey, int limit) throws IOException, InterruptedException;
    List<QueueTrack> randomTracks(int limit, Set<String> excluded) throws IOException, InterruptedException;
    List<QueueTrack> metadataFallback(List<StationSeed> seeds, int limit, Set<String> excluded) throws IOException, InterruptedException;
    List<QueueTrack> expand(ItemKind kind, String key, int limit) throws IOException, InterruptedException;
}
