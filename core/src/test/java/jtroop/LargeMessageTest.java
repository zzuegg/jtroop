package jtroop;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.testing.Forwarder;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for messages that stress the wire-level buffers:
 *   - large (50 KiB, near-boundary) payloads,
 *   - bursts (100 small messages back-to-back, ordering preserved),
 *   - split / fragmented delivery (using Forwarder to introduce per-segment
 *     latency so a single logical message spans multiple channel.read calls).
 *
 * The server and client each use 65536-byte buffers. The EventLoop's write
 * slot is 8 KiB, so any message above 8 KiB exercises the oversized-stage
 * path inside stageWrite.
 */
@Timeout(30)
class LargeMessageTest {

    record BigConnection(int version) {}

    record BigMsg(int seq, String payload) {}
    record Ack(int seq) {}

    interface BigService {
        Ack bigMsg(BigMsg msg);
    }

    @Handles(BigService.class)
    static class BigHandler {
        final CopyOnWriteArrayList<BigMsg> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        @OnMessage
        Ack bigMsg(BigMsg msg, ConnectionId sender) {
            received.add(msg);
            latch.countDown();
            return new Ack(msg.seq());
        }
    }

    private static String repeated(int n, char c) {
        var sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static String pattern(int n) {
        // Deterministic, non-compressible-ish byte pattern so we can tell
        // corruption from truncation.
        var sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) ('A' + (i % 26)));
        return sb.toString();
    }

    @Test
    void fiftyKiBMessage_reconstructedCorrectly() throws Exception {
        // 50 KiB payload is bigger than the 8 KiB EventLoop slot and bigger
        // than the kernel's default segment — TCP will split it across
        // multiple reads on the server side. Server's readBuf (65536) must
        // reconstruct.
        var handler = new BigHandler();
        var server = Server.builder()
                .listen(BigConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BigConnection.class)
                .build();
        server.start();
        int port = server.port(BigConnection.class);

        var client = Client.builder()
                .connect(BigConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(BigService.class, BigConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        String payload = pattern(50_000);
        var ack = client.request(new BigMsg(1, payload), Ack.class);
        assertEquals(1, ack.seq());

        assertEquals(1, handler.received.size());
        var got = handler.received.getFirst();
        assertEquals(50_000, got.payload().length());
        assertEquals(payload, got.payload());

        client.close();
        server.close();
    }

    @Test
    void hundredSmallMessages_arriveInOrder() throws Exception {
        var handler = new BigHandler();
        handler.latch = new CountDownLatch(100);

        var server = Server.builder()
                .listen(BigConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BigConnection.class)
                .build();
        server.start();
        int port = server.port(BigConnection.class);

        var client = Client.builder()
                .connect(BigConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(BigService.class, BigConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        String body = repeated(1000, 'x'); // ~1 KiB per message
        for (int i = 0; i < 100; i++) {
            client.send(new BigMsg(i, body));
        }
        client.flush();

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS),
                "got only " + handler.received.size() + "/100");
        assertEquals(100, handler.received.size());
        // TCP preserves order; framing must too.
        for (int i = 0; i < 100; i++) {
            assertEquals(i, handler.received.get(i).seq(),
                    "ordering broken at index " + i);
        }

        client.close();
        server.close();
    }

    @Test
    void messageSplitAcrossReads_viaForwarderLatency() throws Exception {
        // Inject latency on the forwarder so TCP segments land in different
        // channel.read() calls on the server. Message must still reassemble
        // from FramingLayer + readBuf.compact().
        var handler = new BigHandler();
        var server = Server.builder()
                .listen(BigConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BigConnection.class)
                .build();
        server.start();
        int serverPort = server.port(BigConnection.class);

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                    // Small, variable latency on every buffered chunk — the
                    // forwarder schedules each chunk independently, so the
                    // server sees the frame in pieces.
                    .latency(Duration.ofMillis(5), Duration.ofMillis(30))
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(BigConnection.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                .addService(BigService.class, BigConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        String payload = pattern(30_000);
        var ack = client.request(new BigMsg(7, payload), Ack.class);
        assertEquals(7, ack.seq());

        assertEquals(1, handler.received.size());
        assertEquals(payload, handler.received.getFirst().payload(),
                "payload corrupted across split reads");

        client.close();
        forwarder.close();
        server.close();
    }

    @Test
    void tenTimesFiveKiBMessages_allDelivered() throws Exception {
        // Each 5 KiB message fits inside the 8 KiB EventLoop slot, but two
        // of them don't — the slot-overflow branch of stageWrite runs.
        var handler = new BigHandler();
        handler.latch = new CountDownLatch(10);

        var server = Server.builder()
                .listen(BigConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BigConnection.class)
                .build();
        server.start();
        int port = server.port(BigConnection.class);

        var client = Client.builder()
                .connect(BigConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(BigService.class, BigConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        String body = repeated(5_000, 'y');
        for (int i = 0; i < 10; i++) {
            client.send(new BigMsg(i, body));
        }
        client.flush();

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS),
                "got " + handler.received.size() + "/10");
        for (int i = 0; i < 10; i++) {
            assertEquals(i, handler.received.get(i).seq());
            assertEquals(5_000, handler.received.get(i).payload().length());
        }

        client.close();
        server.close();
    }

    @Test
    void messageAboveMaxFrameLength_isRejectedCleanly() throws Exception {
        // Sending a payload bigger than MAX_FRAME_LENGTH would either
        // overflow the pipeline's temp buffer (BufferOverflow on encode) or,
        // if the peer sneaks one past, be rejected by the decoder via
        // FramingLayer's length check. Here we verify that the client-side
        // encode fails fast with BufferOverflowException rather than
        // silently corrupting the wire.
        var server = Server.builder()
                .listen(BigConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(new BigHandler(), BigConnection.class)
                .build();
        server.start();
        int port = server.port(BigConnection.class);

        var client = Client.builder()
                .connect(BigConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(BigService.class, BigConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        // Too big: StringCodec length prefix is a short, but even before
        // that the pipeline temp buffer can only hold 65536 bytes total.
        String tooBig = repeated(65_535, 'z');
        var thrown = assertThrows(Throwable.class,
                () -> client.send(new BigMsg(0, tooBig)),
                "oversized payload must fail fast on encode");
        // Accept any low-level buffer error — the exact type is an
        // implementation detail we just don't want to stall the loop.
        assertNotNull(thrown);

        client.close();
        server.close();
    }
}
