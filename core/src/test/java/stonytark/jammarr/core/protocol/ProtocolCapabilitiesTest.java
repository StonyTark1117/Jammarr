package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCapabilitiesTest {
    @Test void negotiationIntersectsFeaturesAndTakesTheLowerLimits() {
        ProtocolCapabilities.Negotiated value = ProtocolCapabilities.negotiate(
                ProtocolCapabilities.AUDIO_STREAMING | ProtocolCapabilities.OPTIONAL_CLIENT | (1L << 40),
                ProtocolCapabilities.AUDIO_CHUNK_BYTES, 4);
        assertEquals(ProtocolCapabilities.AUDIO_STREAMING | ProtocolCapabilities.OPTIONAL_CLIENT, value.features());
        assertEquals(ProtocolCapabilities.AUDIO_CHUNK_BYTES, value.audioChunkBytes());
        assertEquals(4, value.chunksPerRequest());
        assertTrue(value.supports(ProtocolCapabilities.OPTIONAL_CLIENT));
        assertFalse(value.supports(ProtocolCapabilities.STATIONS));

        ProtocolCapabilities.Negotiated undersized = ProtocolCapabilities.negotiate(
                ProtocolCapabilities.AUDIO_STREAMING, ProtocolCapabilities.AUDIO_CHUNK_BYTES - 1, 1);
        assertFalse(undersized.supports(ProtocolCapabilities.AUDIO_STREAMING));
    }

    @Test void negotiationCapsOverstatedLimitsAndRejectsNonPositiveValues() {
        ProtocolCapabilities.Negotiated value = ProtocolCapabilities.negotiate(-1L, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(ProtocolCapabilities.SUPPORTED_FEATURES, value.features());
        assertEquals(ProtocolCapabilities.AUDIO_CHUNK_BYTES, value.audioChunkBytes());
        assertEquals(ProtocolCapabilities.CHUNKS_PER_REQUEST, value.chunksPerRequest());
        assertThrows(ProtocolException.class, () -> ProtocolCapabilities.negotiate(0L, 0, 1));
        assertThrows(ProtocolException.class, () -> ProtocolCapabilities.negotiate(0L, 1, 0));
    }
}
