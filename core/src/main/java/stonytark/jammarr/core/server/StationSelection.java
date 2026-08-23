package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;

import java.util.ArrayList;
import java.util.Collections;
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
        final Map<String, Double> scores = new HashMap<String, Double>();
        Map<String, QueueTrack> tracks = new LinkedHashMap<String, QueueTrack>();
        for (List<QueueTrack> ranking : rankings) {
            Set<String> seenInRanking = new HashSet<String>();
            for (int rank = 0; rank < ranking.size(); rank++) {
                QueueTrack track = ranking.get(rank);
                if (!seenInRanking.add(track.key()) || excludedKeys.contains(track.key()) || excludedArtists.contains(track.artist())) continue;
                if (!tracks.containsKey(track.key())) tracks.put(track.key(), track);
                Double old = scores.get(track.key());
                scores.put(track.key(), (old == null ? 0.0 : old) + 1.0 / (RRF_K + rank + 1));
            }
        }
        List<QueueTrack> result = new ArrayList<QueueTrack>(tracks.values());
        Collections.sort(result, new Comparator<QueueTrack>() {
            @Override public int compare(QueueTrack first, QueueTrack second) {
                int score = Double.compare(scores.get(second.key()), scores.get(first.key()));
                return score != 0 ? score : first.key().compareTo(second.key());
            }
        });
        int size = Math.min(result.size(), Math.max(0, limit));
        return Collections.unmodifiableList(new ArrayList<QueueTrack>(result.subList(0, size)));
    }

    public static List<QueueTrack> deduplicatePath(List<List<QueueTrack>> segments, int limit) {
        List<QueueTrack> result = new ArrayList<QueueTrack>(); Set<String> seen = new HashSet<String>();
        for (List<QueueTrack> segment : segments) {
            for (QueueTrack track : segment) {
                if (seen.add(track.key())) result.add(track);
                if (result.size() >= limit) return immutable(result);
            }
        }
        return immutable(result);
    }

    private static List<QueueTrack> immutable(List<QueueTrack> tracks) {
        return Collections.unmodifiableList(new ArrayList<QueueTrack>(tracks));
    }

    private StationSelection() {}
}
