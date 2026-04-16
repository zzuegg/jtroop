package jtroop;

import jtroop.pipeline.layers.FramingLayer;
import jtroop.pipeline.layers.HttpLayer;
import jtroop.server.Server;
import jtroop.service.Handles;
import jtroop.service.OnConnect;
import jtroop.service.OnDisconnect;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial / fuzz-style tests: a buggy or malicious peer sends malformed
 * bytes and we verify the server
 *   (1) does not crash
 *   (2) closes the bad connection cleanly
 *   (3) fires @OnDisconnect for connections that had completed handshake
 *   (4) keeps serving other clients
 *   (5) never lets an exception reach the EventLoop
 */
@Timeout(20)
class MalformedInputTest {

    // --- Test types ---
    record EchoConn(int version) { record Accepted(int version) {} }
    record EchoMsg(String text) {}
    record EchoAck(String text) {}

    interface EchoService { EchoAck echo(EchoMsg msg); }

    @Handles(EchoService.class)
    static class EchoHandler {
        final CopyOnWriteArrayList<EchoMsg> received = new CopyOnWriteArrayList<>();
        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        volatile CountDownLatch disconnectLatch = new CountDownLatch(1);

        @OnMessage EchoAck echo(EchoMsg msg, ConnectionId sender) {
            received.add(msg);
            return new EchoAck(msg.text());
        }
        @OnConnect void onConnect(ConnectionId id) { connects.incrementAndGet(); }
        @OnDisconnect void onDisconnect(ConnectionId id) {
            disconnects.incrementAndGet();
            disconnectLatch.countDown();
        }
    }

    // --- Low-level helper: a raw TCP client that sends whatever bytes we want ---
    private static Socket rawConnect(int port) throws IOException {
        var s = new Socket("localhost", port);
        s.setTcpNoDelay(true);
        return s;
    }

    // ============================================================
    // FRAMING LAYER — malformed framing
    // ============================================================

    /** 1. Pure garbage → FramingLayer rejects, server closes connection, loop survives. */
    @Test
    void randomBytes_closesConnectionAndOtherClientsKeepWorking() throws Exception {
        var handler = new EchoHandler();
        try (var server = Server.builder()
                .listen(EchoConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, EchoConn.class)
                .build()) {
            server.start();
            int port = server.port(EchoConn.class);
            Thread.sleep(100);

            // --- Attacker: sends random garbage (length prefix 0xFFFFFFFF = -1) ---
            try (var bad = rawConnect(port)) {
                byte[] garbage = new byte[32];
                for (int i = 0; i < garbage.length; i++) garbage[i] = (byte) 0xFF;
                bad.getOutputStream().write(garbage);
                bad.getOutputStream().flush();
                // give server time to reject
                Thread.sleep(200);
            }

            // Good client (using the real client) should still work
            try (var client = jtroop.client.Client.builder()
                    .connect(EchoConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(EchoService.class, EchoConn.class)
                    .build()) {
                client.start();
                Thread.sleep(200);
                var ack = client.request(new EchoMsg("still alive"), EchoAck.class);
                assertEquals("still alive", ack.text());
            }
        }
    }

    /** 2. Frame length claims 2GB — must not attempt to allocate or wait forever. */
    @Test
    void frameLengthClaiming2GB_rejected() {
        var layer = new FramingLayer();
        var buf = ByteBuffer.allocate(8);
        buf.putInt(Integer.MAX_VALUE); // 2 GB
        buf.putInt(0);                 // a few body bytes
        buf.flip();
        assertThrows(FramingLayer.FramingException.class, () -> layer.decodeInbound(buf));
    }

    /** 3. Negative frame length → FramingException (not IllegalArgumentException from slice). */
    @Test
    void negativeFrameLength_rejected() {
        var layer = new FramingLayer();
        var buf = ByteBuffer.allocate(4);
        buf.putInt(-1);
        buf.flip();
        assertThrows(FramingLayer.FramingException.class, () -> layer.decodeInbound(buf));
    }

    /** 3b. Exactly-at-boundary frame length → accepted. */
    @Test
    void frameLength_atBoundary_accepted() {
        var layer = new FramingLayer();
        int n = FramingLayer.MAX_FRAME_LENGTH;
        var buf = ByteBuffer.allocate(4 + n);
        buf.putInt(n);
        for (int i = 0; i < n; i++) buf.put((byte) 0);
        buf.flip();
        assertNotNull(layer.decodeInbound(buf));
    }

    /** 3c. One-byte-over-boundary → rejected. */
    @Test
    void frameLength_justOverBoundary_rejected() {
        var layer = new FramingLayer();
        var buf = ByteBuffer.allocate(4);
        buf.putInt(FramingLayer.MAX_FRAME_LENGTH + 1);
        buf.flip();
        assertThrows(FramingLayer.FramingException.class, () -> layer.decodeInbound(buf));
    }

    // ============================================================
    // CODEC — unknown type id, truncated body
    // ============================================================

    /** 4. Unknown message type id → server closes the connection, fires @OnDisconnect. */
    @Test
    void unknownMessageTypeId_closesConnectionFiresDisconnect() throws Exception {
        var handler = new EchoHandler();
        try (var server = Server.builder()
                .listen(EchoConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, EchoConn.class)
                .build()) {
            server.start();
            int port = server.port(EchoConn.class);
            Thread.sleep(100);

            try (var bad = rawConnect(port)) {
                // Server wraps in FramingLayer which expects a 4-byte length
                // then the frame body. Craft a valid frame with bogus type id.
                var frame = ByteBuffer.allocate(16);
                frame.putInt(6);                     // length
                frame.putShort((short) 0x7FFF);      // unknown type id
                frame.putInt(0);                     // payload (ignored — decode fails first)
                frame.flip();
                byte[] bytes = new byte[frame.remaining()];
                frame.get(bytes);
                bad.getOutputStream().write(bytes);
                bad.getOutputStream().flush();
                // @OnConnect fires immediately on TCP accept (no handshake),
                // then @OnDisconnect fires when we close the connection.
                assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS),
                        "Expected @OnDisconnect to fire after malformed frame");
            }
            assertEquals(1, handler.connects.get());
            assertEquals(1, handler.disconnects.get());
        }
    }

    /** 5. Truncated record body — frame claims 6 bytes but payload is smaller. */
    @Test
    void truncatedRecordBody_closesConnection() throws Exception {
        var handler = new EchoHandler();
        try (var server = Server.builder()
                .listen(EchoConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, EchoConn.class)
                .build()) {
            server.start();
            int port = server.port(EchoConn.class);
            Thread.sleep(100);

            try (var bad = rawConnect(port)) {
                // A frame claiming 20 bytes of payload, but real message has
                // only a type id (2 bytes) and a short string (2-byte length
                // prefix but no actual string bytes) → decoder will try to
                // read past the end.
                var frame = ByteBuffer.allocate(24);
                frame.putInt(20);                            // length
                frame.putShort((short) 0x0001);              // any id
                frame.putShort((short) 16);                  // claims 16-byte string
                // only 2 bytes of actual string content, not 16
                frame.put((byte) 'h'); frame.put((byte) 'i');
                // fill rest with zeros so frame is complete per framing
                while (frame.hasRemaining()) frame.put((byte) 0);
                frame.flip();
                byte[] bytes = new byte[frame.remaining()];
                frame.get(bytes);
                bad.getOutputStream().write(bytes);
                bad.getOutputStream().flush();
                assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS));
            }
        }
    }

    // ============================================================
    // HANDSHAKE — bad magic, pre-handshake data
    // ============================================================

    /** 6. Handshake with wrong magic — connection rejected, never fires @OnConnect. */
    @Test
    void handshakeWrongMagic_connectionRejectedNoOnConnect() throws Exception {
        var handler = new EchoHandler();
        try (var server = Server.builder()
                .listen(EchoConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(EchoConn.class, req -> new EchoConn.Accepted(req.version()))
                .addService(handler, EchoConn.class)
                .build()) {
            server.start();
            int port = server.port(EchoConn.class);
            Thread.sleep(100);

            try (var bad = rawConnect(port)) {
                var frame = ByteBuffer.allocate(12);
                frame.putInt(8);                  // framing length
                frame.putInt(0xDEADBEEF);         // WRONG magic
                frame.putInt(0);                  // filler
                frame.flip();
                byte[] bytes = new byte[frame.remaining()];
                frame.get(bytes);
                bad.getOutputStream().write(bytes);
                bad.getOutputStream().flush();
                Thread.sleep(300);
            }

            // Handshake never succeeded → @OnConnect NOT fired, @OnDisconnect NOT fired.
            assertEquals(0, handler.connects.get());
            assertEquals(0, handler.disconnects.get());

            // Server still alive for other clients
            try (var client = jtroop.client.Client.builder()
                    .connect(new EchoConn(1), Transport.tcp("localhost", port), new FramingLayer())
                    .addService(EchoService.class, EchoConn.class)
                    .build()) {
                client.start();
                Thread.sleep(300);
                var ack = client.request(new EchoMsg("after-bad-handshake"), EchoAck.class);
                assertEquals("after-bad-handshake", ack.text());
            }
        }
    }

    /** 7. Garbage bytes before handshake completes — rejected, no crash. */
    @Test
    void garbageBeforeHandshake_rejected() throws Exception {
        var handler = new EchoHandler();
        try (var server = Server.builder()
                .listen(EchoConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(EchoConn.class, req -> new EchoConn.Accepted(req.version()))
                .addService(handler, EchoConn.class)
                .build()) {
            server.start();
            int port = server.port(EchoConn.class);
            Thread.sleep(100);

            try (var bad = rawConnect(port)) {
                byte[] garbage = new byte[32];
                for (int i = 0; i < garbage.length; i++) garbage[i] = (byte) 0xAA;
                bad.getOutputStream().write(garbage);
                bad.getOutputStream().flush();
                Thread.sleep(200);
            }

            assertEquals(0, handler.connects.get());

            // Server still accepting good clients
            try (var client = jtroop.client.Client.builder()
                    .connect(new EchoConn(1), Transport.tcp("localhost", port), new FramingLayer())
                    .addService(EchoService.class, EchoConn.class)
                    .build()) {
                client.start();
                Thread.sleep(300);
                var ack = client.request(new EchoMsg("ok"), EchoAck.class);
                assertEquals("ok", ack.text());
            }
        }
    }

    // ============================================================
    // HTTP — malformed request line, missing/duplicate Content-Length, oversize headers
    // ============================================================

    @Test
    void http_malformedRequestLine_throws() {
        var layer = new HttpLayer();
        var bad = "NOTAREQUEST\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(bad)));
    }

    @Test
    void http_missingContentLengthOnPost_throws() {
        var layer = new HttpLayer();
        var bad = "POST /x HTTP/1.1\r\nHost: h\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);
        assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(bad)));
    }

    @Test
    void http_duplicateConflictingContentLength_throws() {
        var layer = new HttpLayer();
        var bad = ("POST /x HTTP/1.1\r\n"
                + "Content-Length: 3\r\n"
                + "Content-Length: 5\r\n"
                + "\r\nhello").getBytes(StandardCharsets.UTF_8);
        assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(bad)));
    }

    @Test
    void http_duplicateIdenticalContentLength_accepted() {
        // Two identical Content-Length values are equivalent per RFC 7230 §3.3.2
        // (we treat them as harmless — same intent).
        var layer = new HttpLayer();
        var ok = ("POST /x HTTP/1.1\r\n"
                + "Content-Length: 5\r\n"
                + "Content-Length: 5\r\n"
                + "\r\nhello").getBytes(StandardCharsets.UTF_8);
        var frame = layer.decodeInbound(ByteBuffer.wrap(ok));
        assertNotNull(frame);
        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("hello", new String(parsed.body(), StandardCharsets.UTF_8));
    }

    @Test
    void http_headersBiggerThan8K_throws() {
        var layer = new HttpLayer();
        var sb = new StringBuilder();
        sb.append("GET /x HTTP/1.1\r\n");
        // Emit one long header that exceeds MAX_HEADER_SIZE without CRLFCRLF.
        sb.append("X-Big: ");
        sb.append("A".repeat(HttpLayer.MAX_HEADER_SIZE + 100));
        sb.append("\r\n\r\n");
        var bad = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(bad)));
    }

    @Test
    void http_contentLengthExceedsBodyCap_throws() {
        var layer = new HttpLayer();
        var bad = ("POST /x HTTP/1.1\r\n"
                + "Content-Length: " + (HttpLayer.MAX_BODY_SIZE + 1) + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(bad)));
    }

    @Test
    void http_negativeContentLength_throws() {
        var layer = new HttpLayer();
        var bad = ("POST /x HTTP/1.1\r\n"
                + "Content-Length: -5\r\n"
                + "\r\nhi").getBytes(StandardCharsets.UTF_8);
        // '-' is non-numeric → no digits → malformed
        assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(bad)));
    }

    @Test
    void http_getWithoutContentLength_acceptedWithZeroBody() {
        var layer = new HttpLayer();
        var ok = "GET /x HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        var frame = layer.decodeInbound(ByteBuffer.wrap(ok));
        assertNotNull(frame);
        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("GET", parsed.method());
        assertEquals(0, parsed.body().length);
    }

    // ============================================================
    // Isolation — one bad client must not affect others
    // ============================================================

    /** Run several bad clients and several good clients concurrently. */
    @Test
    void manyBadClients_doNotAffectGoodClients() throws Exception {
        var handler = new EchoHandler();
        try (var server = Server.builder()
                .listen(EchoConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, EchoConn.class)
                .build()) {
            server.start();
            int port = server.port(EchoConn.class);
            Thread.sleep(100);

            // Fire off 10 raw garbage connections
            var threads = new Thread[10];
            for (int i = 0; i < threads.length; i++) {
                final int seed = i;
                threads[i] = new Thread(() -> {
                    try (var bad = rawConnect(port)) {
                        byte[] g = new byte[64];
                        for (int j = 0; j < g.length; j++) g[j] = (byte) ((seed * 31 + j) & 0xFF);
                        // Set the length prefix to something nasty on half of them
                        if ((seed & 1) == 0) {
                            g[0] = (byte) 0xFF; g[1] = (byte) 0xFF; g[2] = (byte) 0xFF; g[3] = (byte) 0xFF;
                        }
                        bad.getOutputStream().write(g);
                        bad.getOutputStream().flush();
                        Thread.sleep(100);
                    } catch (Exception _) {}
                });
                threads[i].start();
            }
            for (var t : threads) t.join(3000);

            // Good client does several round-trips
            try (var client = jtroop.client.Client.builder()
                    .connect(EchoConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(EchoService.class, EchoConn.class)
                    .build()) {
                client.start();
                Thread.sleep(300);
                for (int i = 0; i < 5; i++) {
                    var ack = client.request(new EchoMsg("msg-" + i), EchoAck.class);
                    assertEquals("msg-" + i, ack.text());
                }
            }

            // Server is still healthy
            assertTrue(server.isRunning());
        }
    }
}
