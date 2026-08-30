package stonytark.jammarr.core.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class Hashing {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean matchesSha256(byte[] data, String expected) {
        if (expected == null || expected.length() != 64) return false;
        byte[] actual = sha256(data).getBytes(StandardCharsets.US_ASCII);
        byte[] wanted = expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, wanted);
    }

    private static String hex(byte[] digest) {
        char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(result);
    }

    private Hashing() {}
}
