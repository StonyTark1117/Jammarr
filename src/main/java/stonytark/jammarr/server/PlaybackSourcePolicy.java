package stonytark.jammarr.server;

import stonytark.jammarr.network.JammarrPayloads;

import java.util.Deque;
import java.util.List;

public final class PlaybackSourcePolicy {
    public static Selection takeNext(List<QueueTrack> manual, Deque<QueueTrack> generated, boolean adventure) {
        if (!manual.isEmpty()) return new Selection(manual.removeFirst(), JammarrPayloads.PlaybackOrigin.MANUAL);
        QueueTrack track = generated.pollFirst();
        return track == null ? new Selection(null, JammarrPayloads.PlaybackOrigin.NONE)
                : new Selection(track, adventure ? JammarrPayloads.PlaybackOrigin.ADVENTURE : JammarrPayloads.PlaybackOrigin.STATION);
    }

    public static boolean canMove(List<JammarrPayloads.QueueEntry> visible, int index, int delta) {
        int target = index + delta;
        return index >= 0 && target >= 0 && index < visible.size() && target < visible.size()
                && visible.get(index).editable() && visible.get(target).editable();
    }

    public record Selection(QueueTrack track, JammarrPayloads.PlaybackOrigin origin) {}
    private PlaybackSourcePolicy() {}
}
