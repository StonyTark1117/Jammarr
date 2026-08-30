package stonytark.jammarr.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStartPolicyTest {
    @Test void refusesToStartBeforeTheFirstDecodedSampleIsAuthoritative() {
        PlaybackStartPolicy.Decision decision = PlaybackStartPolicy.evaluate(9_900L, 10_000L, 12_000L, 2_000L);
        assertFalse(decision.ready());
        assertEquals(0L, decision.skipMs());
        assertEquals(100L, decision.missingMs());
    }

    @Test void reservesFutureAudioAfterDiscardingLateStartupPcm() {
        PlaybackStartPolicy.Decision shortBuffer = PlaybackStartPolicy.evaluate(10_750L, 10_000L, 2_749L, 2_000L);
        assertFalse(shortBuffer.ready());
        assertEquals(750L, shortBuffer.skipMs());
        assertEquals(1L, shortBuffer.missingMs());

        PlaybackStartPolicy.Decision ready = PlaybackStartPolicy.evaluate(10_750L, 10_000L, 2_750L, 2_000L);
        assertTrue(ready.ready());
        assertEquals(750L, ready.skipMs());
        assertEquals(0L, ready.missingMs());
    }

    @Test void rejectsAStartThatCouldNotActuallyDiscardItsLatePcm() {
        assertTrue(PlaybackStartPolicy.caughtUp(750L, 749L, 1L));
        assertFalse(PlaybackStartPolicy.caughtUp(750L, 748L, 1L));
    }

    @Test void arithmeticCannotWrapAPathologicalTimelineIntoAReadyStart() {
        PlaybackStartPolicy.Decision decision = PlaybackStartPolicy.evaluate(
                Long.MAX_VALUE, -1L, Long.MAX_VALUE, 2_000L);
        assertFalse(decision.ready());
        assertEquals(Long.MAX_VALUE, decision.skipMs());
    }
}
