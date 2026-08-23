package stonytark.jammarr.server;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SecretRedactor {
    public static String message(Throwable error, String secret) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        String message = value.getMessage();
        if (message == null || message.isBlank()) message = value.getClass().getSimpleName();
        return redact(message, secret);
    }

    public static String redact(String value, String secret) {
        String result = value == null ? "" : value;
        if (secret != null && !secret.isBlank()) {
            result = result.replace(secret, "<redacted>");
            result = result.replace(URLEncoder.encode(secret, StandardCharsets.UTF_8), "<redacted>");
        }
        return result
                .replaceAll("(?i)(X-Plex-Token(?:=|%3[Dd]))[^&\\s]+", "$1<redacted>")
                .replaceAll("(?i)(Authorization:\\s*(?:Bearer|Basic)\\s+)[^\\s]+", "$1<redacted>");
    }

    private SecretRedactor() {}
}
