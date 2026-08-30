package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolLimitsTest {
    @AfterEach void clearAcceptanceProperties() {
        System.clearProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_SUPPRESS_HELLO_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_CLIENT_HELLO_DELAY_MS_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_COMMAND_PROBE_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_AUDIO_PROBE_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_AUDIO_LEADER_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY);
    }

    @Test void productionClientHelloUsesCurrentProtocol() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "4");
        assertEquals(ProtocolLimits.VERSION, ProtocolLimits.clientHelloVersion());
    }

    @Test void explicitAcceptanceGateCanOfferAnIncompatibleProtocol() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "4");
        assertEquals(4, ProtocolLimits.clientHelloVersion());
    }

    @Test void invalidAcceptanceOverridesFailClosedToCurrentProtocol() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "not-a-number");
        assertEquals(ProtocolLimits.VERSION, ProtocolLimits.clientHelloVersion());
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "-1");
        assertEquals(ProtocolLimits.VERSION, ProtocolLimits.clientHelloVersion());
    }

    @Test void helloSuppressionRequiresTheExplicitAcceptanceGate() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_SUPPRESS_HELLO_PROPERTY, "true");
        assertEquals(false, ProtocolLimits.clientHelloSuppressed());
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        assertEquals(true, ProtocolLimits.clientHelloSuppressed());
    }

    @Test void clientHelloDelayRequiresAcceptanceModeAndStaysInsideProductionDeadline() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_HELLO_DELAY_MS_PROPERTY, "12000");
        assertEquals(0L, ProtocolLimits.clientHelloDelayMs());
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        assertEquals(12000L, ProtocolLimits.clientHelloDelayMs());
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_HELLO_DELAY_MS_PROPERTY, "60000");
        assertEquals(0L, ProtocolLimits.clientHelloDelayMs());
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_HELLO_DELAY_MS_PROPERTY, "invalid");
        assertEquals(0L, ProtocolLimits.clientHelloDelayMs());
    }

    @Test void commandProbeRequiresTheExplicitAcceptanceGate() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_COMMAND_PROBE_PROPERTY, "true");
        assertEquals(false, ProtocolLimits.commandProbeEnabled());
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        assertEquals(true, ProtocolLimits.commandProbeEnabled());
    }

    @Test void audioProbeAndLeaderRequireTheExplicitAcceptanceGate() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_AUDIO_PROBE_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_AUDIO_LEADER_PROPERTY, "true");
        assertEquals(false, ProtocolLimits.audioProbeEnabled());
        assertEquals(false, ProtocolLimits.audioProbeLeader());
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        assertEquals(true, ProtocolLimits.audioProbeEnabled());
        assertEquals(true, ProtocolLimits.audioProbeLeader());
    }

    @Test void audioControlFileRequiresTheExplicitAudioGate() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY, "/tmp/probe");
        assertEquals("", ProtocolLimits.audioControlFile());
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_AUDIO_PROBE_PROPERTY, "true");
        assertEquals("/tmp/probe", ProtocolLimits.audioControlFile());
    }

    @Test void productionHelloDeadlineIsSixtySeconds() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY, "5000");
        assertEquals(60_000L, ProtocolLimits.serverHelloTimeoutMs());
        assertEquals(1_200L, ProtocolLimits.serverHelloTimeoutTicks());
    }

    @Test void acceptanceMayShortenButNotExtendTheHelloDeadline() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY, "5001");
        assertEquals(5_001L, ProtocolLimits.serverHelloTimeoutMs());
        assertEquals(101L, ProtocolLimits.serverHelloTimeoutTicks());

        System.setProperty(ProtocolLimits.ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY, "60001");
        assertEquals(60_000L, ProtocolLimits.serverHelloTimeoutMs());
        System.setProperty(ProtocolLimits.ACCEPTANCE_HELLO_TIMEOUT_MS_PROPERTY, "invalid");
        assertEquals(60_000L, ProtocolLimits.serverHelloTimeoutMs());
    }
}
