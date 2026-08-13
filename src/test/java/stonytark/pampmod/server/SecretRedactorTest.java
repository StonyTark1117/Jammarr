package stonytark.pampmod.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {
    @Test void redactsPlainAndEncodedTokens() {
        String secret = "a+b/c=";
        String redacted = SecretRedactor.redact("url?X-Plex-Token=a%2Bb%2Fc%3D and " + secret, secret);
        assertFalse(redacted.contains(secret)); assertFalse(redacted.contains("a%2Bb%2Fc%3D"));
        assertTrue(redacted.contains("<redacted>"));
    }
}
