package jtroop;

import jtroop.core.EventLoop;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.HttpLayer;
import jtroop.transport.Transport;
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
 * Tests with JDK's HttpClient (would also work with wrk/curl/ab externally).
 */
@Timeout(10)
class HttpEndToEndTest {

    /** Minimal HTTP server — demonstrates HttpLayer usage. */
    static class HttpServer implements AutoCloseable {
        private final EventLoop eventLoop;
        private final ServerSocketChannel serverChannel;
        private final Pipeline pipeline;
        private volatile boolean running;

        public HttpServer(int port, java.util.function.Function<HttpLayer.ParsedRequest, ByteBuffer> handler) throws IOException {
            eventLoop = new EventLoop("http-server");
            pipeline = new Pipeline(new HttpLayer());
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
                                    client.register(eventLoop.selector(), SelectionKey.OP_READ,
                                            (EventLoop.KeyHandler) rk -> handleRead(rk, readBuf, handler));
                                }
                            });
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        private void handleRead(SelectionKey key, ByteBuffer readBuf,
                                 java.util.function.Function<HttpLayer.ParsedRequest, ByteBuffer> handler) throws IOException {
            var channel = (SocketChannel) key.channel();
            int n;
            try { n = channel.read(readBuf); } catch (IOException e) { n = -1; }
            if (n == -1) { key.cancel(); channel.close(); return; }
            if (n > 0) {
                readBuf.flip();
                var frame = pipeline.decodeInbound(readBuf);
                while (frame != null) {
                    // Keep a position that allows re-reading the frame for parseFrame
                    frame.mark();
                    var req = HttpLayer.parseFrame(frame);
                    var respFrame = handler.apply(req);

                    var wire = ByteBuffer.allocate(65536);
                    pipeline.encodeOutbound(respFrame, wire);
                    wire.flip();
                    while (wire.hasRemaining()) channel.write(wire);

                    frame = pipeline.decodeInbound(readBuf);
                }
                readBuf.compact();
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
}
