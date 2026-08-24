package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.protocol.StatePackets;

import java.util.Deque;
import java.util.List;

/** Shared manual-versus-generated queue ordering and editability rules. */
public final class PlaybackSourcePolicy {
    public static Selection takeNext(List<QueueTrack> manual, Deque<QueueTrack> generated, boolean adventure) {
        if (!manual.isEmpty()) return new Selection(manual.remove(0), StatePackets.PlaybackOrigin.MANUAL);
        QueueTrack track = generated.pollFirst();
        return track == null ? new Selection(null, StatePackets.PlaybackOrigin.NONE)
                : new Selection(track, adventure ? StatePackets.PlaybackOrigin.ADVENTURE
                : StatePackets.PlaybackOrigin.STATION);
    }

    public static boolean canMove(List<StatePackets.QueueEntry> visible, int index, int delta) {
        int target = index + delta;
        return index >= 0 && target >= 0 && index < visible.size() && target < visible.size()
                && visible.get(index).editable() && visible.get(target).editable();
    }

    public static final class Selection {
        private final QueueTrack track;
        private final StatePackets.PlaybackOrigin origin;
        private Selection(QueueTrack track, StatePackets.PlaybackOrigin origin) {
            this.track = track;
            this.origin = origin;
        }
        public QueueTrack track() { return track; }
        public StatePackets.PlaybackOrigin origin() { return origin; }
    }

    private PlaybackSourcePolicy() {}
}
