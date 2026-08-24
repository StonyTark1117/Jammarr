package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolLimitsTest {
    @AfterEach void clearAcceptanceProperties() {
        System.clearProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_SUPPRESS_HELLO_PROPERTY);
    }

    @Test void productionClientHelloIsAlwaysProtocolFive() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "4");
        assertEquals(5, ProtocolLimits.clientHelloVersion());
    }

    @Test void explicitAcceptanceGateCanOfferAnIncompatibleProtocol() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "4");
        assertEquals(4, ProtocolLimits.clientHelloVersion());
    }

    @Test void invalidAcceptanceOverridesFailClosedToProtocolFive() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "not-a-number");
        assertEquals(5, ProtocolLimits.clientHelloVersion());
        System.setProperty(ProtocolLimits.ACCEPTANCE_CLIENT_PROTOCOL_PROPERTY, "-1");
        assertEquals(5, ProtocolLimits.clientHelloVersion());
    }

    @Test void helloSuppressionRequiresTheExplicitAcceptanceGate() {
        System.setProperty(ProtocolLimits.ACCEPTANCE_SUPPRESS_HELLO_PROPERTY, "true");
        assertEquals(false, ProtocolLimits.clientHelloSuppressed());
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        assertEquals(true, ProtocolLimits.clientHelloSuppressed());
    }
}
