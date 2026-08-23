package stonytark.jammarr.core.network;

import stonytark.jammarr.core.server.PlexException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class BoundedStreams {
    public static byte[] read(InputStream input, int maximumBytes, String message) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 16 * 1024));
        copy(input, output, maximumBytes, message);
        return output.toByteArray();
    }

    public static void copy(InputStream input, OutputStream output, long maximumBytes, String message) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > maximumBytes) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, message);
            output.write(buffer, 0, read);
        }
    }

    private BoundedStreams() {}
}
