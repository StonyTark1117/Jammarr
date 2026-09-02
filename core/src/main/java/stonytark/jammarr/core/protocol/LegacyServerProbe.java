package stonytark.jammarr.core.protocol;

import java.util.Locale;
import java.util.regex.Pattern;

/** Vanilla-chat capability probe for versions without safe custom payload negotiation. */
public final class LegacyServerProbe {
    private static final String RESPONSE_PREFIX = "JAMMARR_CAPABILITY:";
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern COLOR = Pattern.compile("(?i)\\u00a7[0-9A-FK-OR]");

    public static String command(int protocol, String nonce) {
        require(protocol, nonce);
        return "/jammarr handshake " + protocol + " " + nonce;
    }

    public static String response(int protocol, String nonce) {
        require(protocol, nonce);
        return "\u00a70" + RESPONSE_PREFIX + protocol + ":" + nonce;
    }

    /** Returns the advertised protocol, or -1 when this is not the matching response. */
    public static int responseProtocol(String message, String nonce) {
        if (!validNonce(nonce) || message == null) return -1;
        String plain = COLOR.matcher(message).replaceAll("");
        String suffix = ":" + nonce;
        int start = plain.indexOf(RESPONSE_PREFIX);
        if (start < 0) return -1;
        String payload = plain.substring(start + RESPONSE_PREFIX.length());
        if (!payload.endsWith(suffix)) return -1;
        String protocol = payload.substring(0, payload.length() - suffix.length());
        try {
            int value = Integer.parseInt(protocol);
            return value > 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static boolean unknownCommand(String message) {
        if (message == null) return false;
        String plain = COLOR.matcher(message).replaceAll("").toLowerCase(Locale.ROOT);
        return plain.contains("unknown command");
    }

    public static boolean validNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    private static void require(int protocol, String nonce) {
        if (protocol <= 0) throw new IllegalArgumentException("protocol must be positive");
        if (!validNonce(nonce)) throw new IllegalArgumentException("nonce must be 32 lowercase hexadecimal characters");
    }

    private LegacyServerProbe() {}
}
