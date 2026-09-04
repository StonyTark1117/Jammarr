package stonytark.jammarr.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendRateCorrectionTest {
    @Test void leavesMixerPositionJitterAlone() {
        for (long drift = -20; drift <= 20; drift++) {
            assertEquals(1.0f, BackendRateCorrection.pitch(drift));
        }
    }

    @Test void keepsSlowAndFastDeviceClocksInsideTheRebufferBoundary() {
        for (double deviceRate : new double[] {0.998, 1.002}) {
            double played = 0;
            float pitch = 1;
            for (long elapsed = 500; elapsed <= 180_000; elapsed += 500) {
                played += 500 * deviceRate * pitch;
                long drift = Math.round(played - elapsed);
                assertTrue(Math.abs(drift) < 150, "Drift forced a stream restart: " + drift);
                pitch = BackendRateCorrection.pitch(drift);
            }
            assertTrue(Math.abs(played - 180_000) < 45);
            // Without rate correction this same device diverges by 360 ms.
            assertTrue(Math.abs(180_000 * deviceRate - 180_000) > 150);
        }
    }

    @Test void boundsCorrectionAndLeavesLargeFailuresToRecovery() {
        assertEquals(1.01f, BackendRateCorrection.pitch(Long.MIN_VALUE));
        assertEquals(0.99f, BackendRateCorrection.pitch(Long.MAX_VALUE));
        double played = 0;
        float pitch = 1;
        for (long elapsed = 500; elapsed <= 2_000; elapsed += 500) {
            played += 500 * 0.5 * pitch;
            pitch = BackendRateCorrection.pitch(Math.round(played - elapsed));
        }
        assertTrue(Math.abs(played - 2_000) > 150,
                "A stalled backend must still reach the existing recovery guard");
    }
}
