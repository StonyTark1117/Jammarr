package stonytark.jammarr.core.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.server.PlexException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlConnectionHttpTransportTest {
    private HttpServer server;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", this::ok);
        server.createContext("/slow", this::slow);
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sendsMethodAndHeadersAndReadsResponse() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Plex-Token", "secret");
        HttpTransport transport = new UrlConnectionHttpTransport();
        try (HttpTransport.Response response = transport.open("POST", url("/ok"), headers, 1_000, 1_000)) {
            assertEquals(200, response.statusCode());
            assertArrayEquals("POST:secret".getBytes(StandardCharsets.UTF_8),
                    BoundedStreams.read(response.body(), 64, "too large"));
        }
    }

    @Test void enforcesReadTimeoutAfterHeaders() throws Exception {
        HttpTransport transport = new UrlConnectionHttpTransport();
        try (HttpTransport.Response response = transport.open("GET", url("/slow"),
                Collections.<String, String>emptyMap(), 1_000, 30)) {
            assertThrows(SocketTimeoutException.class,
                    () -> BoundedStreams.read(response.body(), 64, "too large"));
        }
    }

    @Test void rejectsBodiesBeyondLimitWithoutModernJdkApis() {
        byte[] body = new byte[65];
        PlexException error = assertThrows(PlexException.class, () -> BoundedStreams.read(
                new java.io.ByteArrayInputStream(body), 64, "too large"));
        assertEquals(PlexException.Kind.INVALID_RESPONSE, error.kind());
        assertEquals("too large", error.getMessage());
    }

    private URL url(String path) throws IOException {
        return new URL("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void ok(HttpExchange exchange) throws IOException {
        byte[] body = (exchange.getRequestMethod() + ":" + exchange.getRequestHeaders().getFirst("X-Plex-Token"))
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void slow(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().flush();
        try { Thread.sleep(150); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { exchange.getResponseBody().write('x'); }
        catch (IOException ignored) {}
        exchange.close();
    }
}
