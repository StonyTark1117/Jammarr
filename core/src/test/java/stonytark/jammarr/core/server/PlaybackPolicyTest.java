package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.model.RestartMode;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackPolicyTest {
    @Test void appliesEveryRestartMode() {
        assertRestoration(RestartPolicy.restore(RestartMode.RESTART_TRACK, 4_000, true), false, 0, false);
        assertRestoration(RestartPolicy.restore(RestartMode.CLEAR, 4_000, true), true, 0, false);
        assertRestoration(RestartPolicy.restore(RestartMode.RESUME_POSITION, 4_000, true), false, 4_000, true);
    }

    @Test void timelineSchedulesLateStartAndPreservesPauseResumePosition() {
        AtomicLong now = new AtomicLong(10_000); PlaybackTimeline timeline = new PlaybackTimeline(now::get);
        timeline.schedule(30_000, 0, false, 5_000); assertEquals(15_000, timeline.startedAtMs()); assertEquals(0, timeline.positionMs());
        now.set(18_000); assertEquals(3_000, timeline.positionMs()); timeline.pause(); now.set(25_000); assertEquals(3_000, timeline.positionMs());
        timeline.resume(); now.set(27_000); assertEquals(5_000, timeline.positionMs());
    }

    @Test void emptyServerOnlyResumesAutomaticallyPausedPlayback() {
        assertTrue(EmptyServerPausePolicy.shouldPause(true, true, true, false));
        assertFalse(EmptyServerPausePolicy.shouldPause(false, true, true, false));
        assertTrue(EmptyServerPausePolicy.shouldResume(true, false));
        assertFalse(EmptyServerPausePolicy.shouldResume(false, false));
    }

    @Test void missingItemsSkipWhileOutagesWait() {
        assertEquals(TrackFailurePolicy.Action.SKIP_TRACK, TrackFailurePolicy.action(new PlexException(PlexException.Kind.NOT_FOUND, "gone")));
        assertEquals(TrackFailurePolicy.Action.WAIT_FOR_PLEX, TrackFailurePolicy.action(new PlexException(PlexException.Kind.OFFLINE, "down")));
        assertEquals(TrackFailurePolicy.Action.WAIT_FOR_PLEX, TrackFailurePolicy.action(new RuntimeException(new PlexException(PlexException.Kind.AUTHENTICATION, "bad"))));
    }

    private static void assertRestoration(RestartPolicy.Restoration value, boolean clear, long position, boolean paused) {
        assertEquals(clear, value.clearQueue()); assertEquals(position, value.positionMs()); assertEquals(paused, value.paused());
    }
}
