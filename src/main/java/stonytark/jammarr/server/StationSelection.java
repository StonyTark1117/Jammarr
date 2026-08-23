package stonytark.jammarr.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StationSelection {
    private static final double RRF_K = 60.0;

    public static List<QueueTrack> reciprocalRankFusion(List<List<QueueTrack>> rankings, Set<String> excludedKeys,
                                                         Set<String> excludedArtists, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, QueueTrack> tracks = new LinkedHashMap<>();
        for (List<QueueTrack> ranking : rankings) {
            Set<String> seenInRanking = new HashSet<>();
            for (int rank = 0; rank < ranking.size(); rank++) {
                QueueTrack track = ranking.get(rank);
                if (!seenInRanking.add(track.key()) || excludedKeys.contains(track.key()) || excludedArtists.contains(track.artist())) continue;
                tracks.putIfAbsent(track.key(), track);
                scores.merge(track.key(), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        return tracks.values().stream().sorted(Comparator.comparingDouble((QueueTrack track) -> scores.getOrDefault(track.key(), 0.0)).reversed()
                        .thenComparing(QueueTrack::key)).limit(Math.max(0, limit)).toList();
    }

    public static List<QueueTrack> deduplicatePath(List<List<QueueTrack>> segments, int limit) {
        List<QueueTrack> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (List<QueueTrack> segment : segments) {
            for (QueueTrack track : segment) {
                if (seen.add(track.key())) result.add(track);
                if (result.size() >= limit) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    private StationSelection() {}
}
