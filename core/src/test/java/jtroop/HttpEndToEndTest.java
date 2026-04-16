package jtroop;

import jtroop.core.EventLoop;
import jtroop.pipeline.layers.HttpLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end HTTP server built on jtroop's EventLoop + HttpLayer.
 * Tests with JDK's HttpClient and raw sockets for wire-level behaviours.
 */
@Timeout(10)
class HttpEndToEndTest {

    /** Minimal HTTP server — demonstrates HttpLayer usage. */
    static class HttpServer implements AutoCloseable {
        private final EventLoop eventLoop;
        private final ServerSocketChannel serverChannel;
        private volatile boolean running;

        public HttpServer(int port, java.util.function.Function<HttpLayer.ParsedRequest, ByteBuffer> handler) throws IOException {
            eventLoop = new EventLoop("http-server");
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            running = true;
            eventLoop.start();
            eventLoop.submit(() -> {
                try {
                    serverChannel.configureBlocking(false);
                    serverChannel.register(eventLoop.selector(), SelectionKey.OP_ACCEPT,
                            (EventLoop.KeyHandler) key -> {
                                if (key.isAcceptable()) {
                                    var client = serverChannel.accept();
                                    if (client == null) return;
                                    client.configureBlocking(false);
                                    var readBuf = ByteBuffer.allocate(65536);
                                    // Each connection gets its own HttpLayer so decode/encode
                                    // state (keep-alive, HEAD) is not shared across sockets.
                                    var connLayer = new HttpLayer();
                                    client.register(eventLoop.selector(), SelectionKey.OP_READ,
                                            (EventLoop.KeyHandler) rk -> handleRead(rk, readBuf, connLayer, handler));
                                }
                            });
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        private void handleRead(SelectionKey key, ByteBuffer readBuf, HttpLayer layer,
                                 java.util.function.Function<HttpLayer.ParsedRequest, ByteBuffer> handler) throws IOException {
            var channel = (SocketChannel) key.channel();
            int n;
            try { n = channel.read(readBuf); } catch (IOException e) { n = -1; }
            if (n == -1) { key.cancel(); channel.close(); return; }
            if (n > 0) {
                readBuf.flip();
                boolean closeAfter = false;
                while (true) {
                    ByteBuffer frame;
                    try {
                        frame = layer.decodeInbound(readBuf);
                    } catch (HttpLayer.HttpProtocolException e) {
                        var errFrame = HttpLayer.buildErrorResponseFrame(e.status());
                        var wire = ByteBuffer.allocate(512);
                        layer.encodeOutbound(errFrame, wire);
                        wire.flip();
                        while (wire.hasRemaining()) channel.write(wire);
                        closeAfter = true;
                        break;
                    }
                    if (frame == null) break;
                    frame.mark();
                    var req = HttpLayer.parseFrame(frame);
                    var respFrame = handler.apply(req);

                    var wire = ByteBuffer.allocate(65536);
                    layer.encodeOutbound(respFrame, wire);
                    wire.flip();
                    while (wire.hasRemaining()) channel.write(wire);

                    // Respect the decoded keep-alive preference.
                    if (!layer.lastKeepAlive()) { closeAfter = true; break; }
                }
                readBuf.compact();
                if (closeAfter) { key.cancel(); channel.close(); }
            }
        }

        public int port() throws IOException {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        }

        @Override
        public void close() {
            running = false;
            try { serverChannel.close(); } catch (IOException _) {}
            eventLoop.close();
        }
    }

    @Test
    void httpServer_respondsToGet() throws Exception {
        try (var server = new HttpServer(0, req -> {
            if (req.path().equals("/hello")) {
                return HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                        "world".getBytes(StandardCharsets.UTF_8));
            }
            return HttpLayer.buildResponseFrame(404, "Not Found", "text/plain", new byte[0]);
        })) {
            int port = server.port();
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hello"))
                    .GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertEquals("world", resp.body());
        }
    }

    @Test
    void httpServer_respondsToPost() throws Exception {
        try (var server = new HttpServer(0, req -> {
            if (req.method().equals("POST")) {
                var echo = new String(req.body(), StandardCharsets.UTF_8);
                return HttpLayer.buildResponseFrame(200, "OK", "application/json",
                        ("{\"echo\":\"" + echo + "\"}").getBytes(StandardCharsets.UTF_8));
            }
            return HttpLayer.buildResponseFrame(405, "Method Not Allowed", "text/plain", new byte[0]);
        })) {
            int port = server.port();
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                    .POST(HttpRequest.BodyPublishers.ofString("hello"))
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertEquals("{\"echo\":\"hello\"}", resp.body());
        }
    }

    @Test
    void httpServer_handlesMultipleRequests() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = new HttpServer(0, req -> {
            count.incrementAndGet();
            return HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                    "ok".getBytes(StandardCharsets.UTF_8));
        })) {
            int port = server.port();
            var client = HttpClient.newHttpClient();
            for (int i = 0; i < 10; i++) {
                var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + i))
                        .GET().build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, resp.statusCode());
            }
            assertEquals(10, count.get());
        }
    }

    @Test
    void httpServer_returnsJsonBody() throws Exception {
        try (var server = new HttpServer(0, req -> {
            var json = "{\"message\":\"Hello, World!\"}";
            return HttpLayer.buildResponseFrame(200, "OK", "application/json",
                    json.getBytes(StandardCharsets.UTF_8));
        })) {
            int port = server.port();
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/json")).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertEquals("application/json", resp.headers().firstValue("Content-Type").orElse(""));
            assertEquals("{\"message\":\"Hello, World!\"}", resp.body());
        }
    }

    // ---------- Raw-socket tests for wire-level behaviours ----------

    private static java.util.function.Function<HttpLayer.ParsedRequest, ByteBuffer> helloHandler(String body) {
        return req -> HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** Opens a socket, writes {@code request}, reads until close, returns the response. */
    private static String exchange(int port, String request) throws IOException {
        try (var sock = new java.net.Socket("localhost", port)) {
            sock.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
            var baos = new java.io.ByteArrayOutputStream();
            var buf = new byte[4096];
            int n;
            while ((n = sock.getInputStream().read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void rawSocket_headRequest_omitsBody() throws Exception {
        try (var server = new HttpServer(0, helloHandler("the body bytes"))) {
            var resp = exchange(server.port(),
                    "HEAD /x HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"), resp);
            assertTrue(resp.contains("Content-Length: 14"), resp);
            assertFalse(resp.contains("the body bytes"), "HEAD response must not include body: " + resp);
        }
    }

    @Test
    void rawSocket_http10_noHostRequired() throws Exception {
        try (var server = new HttpServer(0, helloHandler("hi"))) {
            var resp = exchange(server.port(), "GET /x HTTP/1.0\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"), resp);
            assertTrue(resp.contains("Connection: close"), resp);
            assertTrue(resp.endsWith("hi"), resp);
        }
    }

    @Test
    void rawSocket_chunkedEncoding_rejected501() throws Exception {
        try (var server = new HttpServer(0, helloHandler("x"))) {
            var resp = exchange(server.port(),
                    "POST /x HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                  + "5\r\nhello\r\n0\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 501 "), resp);
        }
    }

    @Test
    void rawSocket_longUri_rejected() throws Exception {
        try (var server = new HttpServer(0, helloHandler("x"))) {
            var longPath = "/" + "a".repeat(HttpLayer.MAX_HEADER_SIZE + 10);
            var resp = exchange(server.port(),
                    "GET " + longPath + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 414 ") || resp.startsWith("HTTP/1.1 431 "), resp);
        }
    }

    @Test
    void rawSocket_missingHostOnHttp11_rejected400() throws Exception {
        try (var server = new HttpServer(0, helloHandler("x"))) {
            var resp = exchange(server.port(), "GET /x HTTP/1.1\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 400 "), resp);
        }
    }

    @Test
    void rawSocket_leadingCrlf_tolerated() throws Exception {
        try (var server = new HttpServer(0, helloHandler("ok"))) {
            var resp = exchange(server.port(),
                    "\r\n\r\nGET /x HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"), resp);
            assertTrue(resp.endsWith("ok"), resp);
        }
    }

    @Test
    void rawSocket_pipelinedRequests_bothAnswered() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = new HttpServer(0, req -> {
            count.incrementAndGet();
            return HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                    ("r" + count.get()).getBytes(StandardCharsets.UTF_8));
        })) {
            var resp = exchange(server.port(),
                    "GET /a HTTP/1.1\r\nHost: localhost\r\n\r\n"
                  + "GET /b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            int first = resp.indexOf("HTTP/1.1 200 OK");
            int second = resp.indexOf("HTTP/1.1 200 OK", first + 1);
            assertTrue(first >= 0 && second > first, "expected two responses: " + resp);
            assertEquals(2, count.get());
        }
    }

    @Test
    void rawSocket_http09_rejected400() throws Exception {
        try (var server = new HttpServer(0, helloHandler("x"))) {
            var resp = exchange(server.port(), "GET /\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 400 "), resp);
        }
    }

    @Test
    void rawSocket_caseInsensitiveContentLength_uppercase() throws Exception {
        try (var server = new HttpServer(0, req -> HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                req.body()))) {
            var resp = exchange(server.port(),
                    "POST /x HTTP/1.1\r\nHost: localhost\r\nCONTENT-LENGTH: 5\r\nConnection: close\r\n\r\nhello");
            assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"), resp);
            assertTrue(resp.endsWith("hello"), resp);
        }
    }
}
