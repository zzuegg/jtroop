package jtroop.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for TCP back-pressure handling in the EventLoop write path.
 *
 * Bug: {@code flushPendingWrites} used to unconditionally clear {@code pendingWrite}
 * after {@code channel.write()}, even when the non-blocking write returned early
 * (receiver's socket buffer was full). The remainder sat in the staging buffer
 * and was never flushed unless more data arrived.
 *
 * Similarly, {@code stageWrite} used {@code buf.clear()} after a partial drain,
 * silently dropping un-flushed bytes.
 *
 * These tests pin the correct behaviour:
 *  - no byte corruption under a slow reader,
 *  - no data silently lost,
 *  - a slow consumer on one connection does not stall other connections.
 */
@Timeout(60)
class EventLoopBackPressureTest {

    // Framing: each "message" is a 4-byte big-endian sequence id followed by
    // PAYLOAD_SIZE bytes whose value equals (seq % 251). Receivers verify both.
    private static final int PAYLOAD_SIZE = 1020;
    private static final int FRAME_SIZE = 4 + PAYLOAD_SIZE;

    private static void fillFrame(ByteBuffer buf, int seq) {
        buf.clear();
        buf.putInt(seq);
        byte b = (byte) (seq % 251);
        for (int i = 0; i < PAYLOAD_SIZE; i++) buf.put(b);
        buf.flip();
    }

    /**
     * One slow consumer at ~1 KB/s, producer at up to ~1 MB/s for ~2 s. Verify
     * that every byte the consumer actually receives matches the expected
     * sequence (no torn or reordered data), and that the EventLoop stays healthy.
     *
     * Back-pressure is observable: the producer's staging buffer and TCP socket
     * buffer fill up, {@code channel.write()} starts returning 0, and the
     * {@code stageWrite} fast-path stops accepting new frames until the
     * EventLoop can drain.
     */
    @Test
    void slowReader_noCorruption_backPressureObservable() throws Exception {
        try (var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            // Consumer side — runs on its own thread, reads slowly.
            var received = new AtomicLong(0);
            var corruption = new AtomicBoolean(false);
            var consumerReady = new CountDownLatch(1);
            var consumerDone = new AtomicBoolean(false);
            Thread consumer = new Thread(() -> {
                try (var sock = server.accept()) {
                    sock.configureBlocking(true);
                    consumerReady.countDown();
                    var readBuf = ByteBuffer.allocate(FRAME_SIZE);
                    while (!consumerDone.get()) {
                        readBuf.clear();
                        while (readBuf.hasRemaining()) {
                            int n = sock.read(readBuf);
                            if (n < 0) return;
                            if (n == 0) { Thread.sleep(1); continue; }
                        }
                        readBuf.flip();
                        int seq = readBuf.getInt();
                        byte expected = (byte) (seq % 251);
                        for (int i = 0; i < PAYLOAD_SIZE; i++) {
                            if (readBuf.get() != expected) {
                                corruption.set(true);
                                return;
                            }
                        }
                        long got = received.incrementAndGet();
                        // ~1 KB/s — sleep 1 ms between frames of ~1 KB.
                        // (FRAME_SIZE = 1024 bytes; 1 ms cadence ≈ 1 MB/s read limit
                        //  but the OS socket buffer and our slow sleeps below force
                        //  back-pressure regardless — see deliberate cadence below.)
                        if ((got & 1) == 0) Thread.sleep(10); // ~100 KB/s effective
                    }
                } catch (Exception _) {
                    // socket closed — fine
                }
            }, "slow-consumer");
            consumer.setDaemon(true);
            consumer.start();

            // Producer — sends via EventLoop.
            try (var loop = new EventLoop("bp-producer", 4)) {
                loop.start();
                var sender = SocketChannel.open();
                sender.configureBlocking(true);
                sender.connect(new InetSocketAddress("127.0.0.1", port));
                sender.configureBlocking(false);
                assertTrue(consumerReady.await(2, TimeUnit.SECONDS));
                loop.registerWriteTarget(0, sender);

                var frame = ByteBuffer.allocate(FRAME_SIZE);
                long endAt = System.nanoTime() + 2_000_000_000L;
                int seq = 0;
                long sent = 0;
                while (System.nanoTime() < endAt) {
                    fillFrame(frame, seq);
                    // stageWrite must not drop bytes under back-pressure.
                    loop.stageWrite(0, frame);
                    seq++;
                    sent++;
                    // Try to push ~1 MB/s by tight looping; OS buffers + slow
                    // consumer will force stageWrite to spin on back-pressure.
                }
                loop.flush();
                // Allow drain — but bounded; the consumer is slow so not all will land.
                Thread.sleep(300);
                sender.close();
                consumerDone.set(true);
                consumer.join(3_000);

                long got = received.get();
                assertFalse(corruption.get(),
                        "byte corruption observed — back-pressure path is losing or shifting bytes");
                assertTrue(got > 0, "consumer received no frames at all");
                // Back-pressure observable: with a slow reader and tight producer loop,
                // the producer must have been held back. That is: received << sent.
                assertTrue(got < sent,
                        "expected received < sent under slow-reader back-pressure (sent=" + sent + ", got=" + got + ")");
                assertTrue(loop.isRunning(), "EventLoop must stay healthy under back-pressure");
            }
        }
    }

    /**
     * Many concurrent connections: one slow consumer, the rest fast. The fast
     * consumers must not stall because of the slow one. Historically, the bug
     * in flushPendingWrites cleared pendingWrite incorrectly, which meant the
     * slow slot's unflushed bytes would sit around until new writes arrived —
     * not a cross-connection stall per se, but combined with stageWrite's
     * clear() the bytes were lost. This test pins correctness for the
     * multi-connection case.
     */
    @Test
    void oneSlowConsumer_doesNotStallOthers() throws Exception {
        final int fastCount = 3;
        final int totalConns = fastCount + 1; // + 1 slow
        try (var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            var consumers = new ArrayList<Thread>();
            var receivedCounts = new AtomicLong[totalConns];
            var corruption = new AtomicBoolean(false);
            var allConnected = new CountDownLatch(totalConns);
            var consumersDone = new AtomicBoolean(false);

            // Server-side accept loop spawns one consumer thread per connection
            // in arrival order.
            server.configureBlocking(true);
            var acceptThread = new Thread(() -> {
                int idx = 0;
                while (idx < totalConns) {
                    try {
                        var sock = server.accept();
                        final int connIdx = idx;
                        receivedCounts[connIdx] = new AtomicLong(0);
                        boolean slow = (connIdx == 0); // first connection is the slow one
                        var t = new Thread(() -> {
                            try (sock) {
                                sock.configureBlocking(true);
                                allConnected.countDown();
                                var rb = ByteBuffer.allocate(FRAME_SIZE);
                                while (!consumersDone.get()) {
                                    rb.clear();
                                    while (rb.hasRemaining()) {
                                        int n = sock.read(rb);
                                        if (n < 0) return;
                                        if (n == 0) { Thread.sleep(1); continue; }
                                    }
                                    rb.flip();
                                    int seq = rb.getInt();
                                    byte expected = (byte) (seq % 251);
                                    for (int k = 0; k < PAYLOAD_SIZE; k++) {
                                        if (rb.get() != expected) {
                                            corruption.set(true);
                                            return;
                                        }
                                    }
                                    receivedCounts[connIdx].incrementAndGet();
                                    if (slow) Thread.sleep(20); // ~50 frames/s ≈ 50 KB/s
                                }
                            } catch (Exception _) {}
                        }, "consumer-" + connIdx);
                        t.setDaemon(true);
                        t.start();
                        consumers.add(t);
                        idx++;
                    } catch (Exception _) { return; }
                }
            }, "accept-loop");
            acceptThread.setDaemon(true);
            acceptThread.start();

            try (var loop = new EventLoop("bp-multi", totalConns + 1)) {
                loop.start();
                var senders = new SocketChannel[totalConns];
                for (int i = 0; i < totalConns; i++) {
                    var c = SocketChannel.open();
                    c.configureBlocking(true);
                    c.connect(new InetSocketAddress("127.0.0.1", port));
                    c.configureBlocking(false);
                    senders[i] = c;
                    loop.registerWriteTarget(i, c);
                }
                assertTrue(allConnected.await(3, TimeUnit.SECONDS));

                // Each slot gets its own producer thread, round-robin through
                // the EventLoop's writeBuffers.
                var producerDone = new CountDownLatch(totalConns);
                var sentCounts = new long[totalConns];
                long durationNs = 2_000_000_000L;
                long startAt = System.nanoTime();
                for (int i = 0; i < totalConns; i++) {
                    final int slot = i;
                    var t = new Thread(() -> {
                        var frame = ByteBuffer.allocate(FRAME_SIZE);
                        int seq = 0;
                        long deadline = startAt + durationNs;
                        while (System.nanoTime() < deadline) {
                            fillFrame(frame, seq);
                            try {
                                loop.stageWrite(slot, frame);
                            } catch (IllegalStateException backPressureTimeout) {
                                // Acceptable for the slow slot: producer gave up.
                                break;
                            }
                            seq++;
                        }
                        sentCounts[slot] = seq;
                        producerDone.countDown();
                    }, "producer-" + i);
                    t.setDaemon(true);
                    t.start();
                }
                assertTrue(producerDone.await(10, TimeUnit.SECONDS),
                        "producers must terminate within bounded time — slow slot must not deadlock fast ones");
                loop.flush();
                Thread.sleep(500); // drain
                for (var s : senders) { try { s.close(); } catch (Exception _) {} }
                consumersDone.set(true);
                for (var t : consumers) t.join(3_000);

                assertFalse(corruption.get(), "byte corruption on one of the connections");

                // Fast consumers must have received substantially more than the slow one.
                long slowGot = receivedCounts[0].get();
                for (int i = 1; i < totalConns; i++) {
                    long fastGot = receivedCounts[i].get();
                    assertTrue(fastGot > 0, "fast consumer " + i + " received nothing");
                    assertTrue(fastGot > slowGot,
                            "fast consumer " + i + " (" + fastGot + " frames) must out-throughput slow consumer ("
                                    + slowGot + " frames) — slow slot is stalling fast slots");
                }
                assertTrue(loop.isRunning());
            }
        }
    }

    /**
     * Direct unit test of the flush-and-compact path: stage more bytes than the
     * staging buffer can hold across several rounds, while the receiver only
     * consumes slowly. Every byte the receiver reads must match the sequence —
     * this is what the pre-fix {@code buf.clear()} in stageWrite broke. Also
     * asserts that the receiver sees every byte that was staged, i.e. no data
     * is silently dropped.
     */
    @Test
    void stageWrite_neverLosesBytes_whenBufferFills() throws Exception {
        try (var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            server.configureBlocking(true);

            final int total = 64 * 1024; // > 8 KB staging buffer, forces spill
            var corruption = new AtomicBoolean(false);
            var readCount = new AtomicLong(0);
            var consumer = new Thread(() -> {
                try (var sock = server.accept()) {
                    sock.configureBlocking(true);
                    var b = ByteBuffer.allocate(256);
                    long expectedIdx = 0;
                    while (expectedIdx < total) {
                        b.clear();
                        int n = sock.read(b);
                        if (n < 0) return;
                        if (n == 0) continue;
                        b.flip();
                        for (int k = 0; k < n; k++) {
                            byte got = b.get();
                            byte want = (byte) (expectedIdx % 251);
                            if (got != want) {
                                corruption.set(true);
                                return;
                            }
                            expectedIdx++;
                        }
                        readCount.set(expectedIdx);
                        if ((expectedIdx & 1023) == 0) Thread.sleep(5); // slow pull
                    }
                    readCount.set(expectedIdx);
                } catch (Exception _) {}
            }, "byte-consumer");
            consumer.setDaemon(true);
            consumer.start();

            try (var loop = new EventLoop("bp-bytes", 2)) {
                loop.start();
                var sender = SocketChannel.open();
                sender.configureBlocking(true);
                sender.connect(new InetSocketAddress("127.0.0.1", port));
                sender.configureBlocking(false);
                loop.registerWriteTarget(0, sender);

                int chunkSize = 500; // not a power-of-two, stresses compact/slow-path
                var chunk = ByteBuffer.allocate(chunkSize);
                long idx = 0;
                int remaining = total;
                while (remaining > 0) {
                    int n = Math.min(chunkSize, remaining);
                    chunk.clear();
                    for (int j = 0; j < n; j++) chunk.put((byte) ((idx + j) % 251));
                    chunk.flip();
                    loop.stageWrite(0, chunk);
                    idx += n;
                    remaining -= n;
                }
                loop.flush();

                // Wait for the consumer to read all staged bytes (bounded).
                long waitDeadline = System.nanoTime() + 15_000_000_000L;
                while (readCount.get() < total
                        && System.nanoTime() < waitDeadline
                        && !corruption.get()) {
                    Thread.sleep(50);
                }
                consumer.join(3_000);
                sender.close();
            }
            assertFalse(corruption.get(),
                    "byte corruption — stageWrite re-used bytes across the spill boundary");
            assertEquals(total, readCount.get(),
                    "consumer received fewer bytes than were staged — silent data loss");
        }
    }
}
