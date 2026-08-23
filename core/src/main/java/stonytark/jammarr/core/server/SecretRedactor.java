package stonytark.jammarr.core.server;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public final class SecretRedactor {
    public static String message(Throwable error, String secret) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        String message = value.getMessage();
        if (blank(message)) message = value.getClass().getSimpleName();
        return redact(message, secret);
    }

    public static String redact(String value, String secret) {
        String result = value == null ? "" : value;
        if (!blank(secret)) {
            result = result.replace(secret, "<redacted>");
            result = result.replace(urlEncode(secret), "<redacted>");
        }
        return result
                .replaceAll("(?i)(X-Plex-Token(?:=|%3[Dd]))[^&\\s]+", "$1<redacted>")
                .replaceAll("(?i)(Authorization:\\s*(?:Bearer|Basic)\\s+)[^\\s]+", "$1<redacted>");
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private SecretRedactor() {}
}
