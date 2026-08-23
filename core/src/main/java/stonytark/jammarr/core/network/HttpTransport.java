package stonytark.jammarr.core.network;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

/** Small HTTP boundary usable by Java 8 and every supported loader. */
public interface HttpTransport {
    Response open(String method, URL url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs)
            throws IOException;

    interface Response extends Closeable {
        int statusCode();
        long contentLength();
        InputStream body();
    }
}
