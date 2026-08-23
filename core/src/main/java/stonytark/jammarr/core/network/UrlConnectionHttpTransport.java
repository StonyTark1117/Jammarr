package stonytark.jammarr.core.network;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

/** Java 8 HTTP implementation with default JVM TLS verification and bounded timeouts. */
public final class UrlConnectionHttpTransport implements HttpTransport {
    @Override public Response open(String method, URL url, Map<String, String> headers,
                                   int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection)) throw new IOException("Only HTTP(S) connections are supported");
        final HttpURLConnection connection = (HttpURLConnection) raw;
        connection.setConnectTimeout(positiveTimeout(connectTimeoutMs));
        connection.setReadTimeout(positiveTimeout(readTimeoutMs));
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException failure) {
            connection.disconnect();
            throw failure;
        }
        InputStream stream;
        try {
            stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        } catch (IOException failure) {
            connection.disconnect();
            throw failure;
        }
        if (stream == null) stream = new ByteArrayInputStream(new byte[0]);
        return new ConnectionResponse(connection, status, connection.getContentLengthLong(), stream);
    }

    private static int positiveTimeout(int timeoutMs) {
        return Math.max(1, timeoutMs);
    }

    private static final class ConnectionResponse implements Response {
        private final HttpURLConnection connection;
        private final int status;
        private final long contentLength;
        private final InputStream body;

        private ConnectionResponse(HttpURLConnection connection, int status, long contentLength, InputStream body) {
            this.connection = connection;
            this.status = status;
            this.contentLength = contentLength;
            this.body = body;
        }

        @Override public int statusCode() { return status; }
        @Override public long contentLength() { return contentLength; }
        @Override public InputStream body() { return body; }

        @Override public void close() throws IOException {
            try {
                body.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
