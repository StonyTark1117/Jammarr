package stonytark.jammarr.network;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean matchesSha256(byte[] data, String expected) {
        if (expected == null || expected.length() != 64) return false;
        byte[] actual = sha256(data).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] wanted = expected.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, wanted);
    }

    private Hashing() {}
}
