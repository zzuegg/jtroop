package jtroop.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the stageWrite paths that matter for large / bursty messages.
 * The slot buffer is 8 KiB; these tests cover what happens when a single
 * message is smaller than, equal to, and larger than that.
 */
@Timeout(15)
class EventLoopStageWriteTest {

    /**
     * Harness: accept one client, return (loop, slot, connected-server-side
     * channel for reading what was written).
     */
    private static final class Harness implements AutoCloseable {
        final EventLoop loop;
        final ServerSocketChannel listener;
        final SocketChannel clientSide;  // used for stageWrite
        final SocketChannel serverSide;  // used for reading back the bytes

        Harness() throws IOException {
            this.loop = new EventLoop("test-loop");
            this.listener = ServerSocketChannel.open();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            this.clientSide = SocketChannel.open();
            clientSide.configureBlocking(true);
            clientSide.connect(new InetSocketAddress("127.0.0.1", port));
            clientSide.configureBlocking(false);

            this.serverSide = listener.accept();
            serverSide.configureBlocking(true); // blocking reads in tests

            loop.registerWriteTarget(0, clientSide);
            loop.start();
        }

        byte[] readExactly(int n) throws IOException {
            var buf = ByteBuffer.allocate(n);
            while (buf.hasRemaining()) {
                int got = serverSide.read(buf);
                if (got < 0) throw new IOException("EOF after " + buf.position() + "/" + n);
            }
            return buf.array();
        }

        @Override
        public void close() throws IOException {
            loop.close();
            try { clientSide.close(); } catch (IOException _) {}
            try { serverSide.close(); } catch (IOException _) {}
            try { listener.close(); } catch (IOException _) {}
        }
    }

    @Test
    void smallMessage_staysInSlotBuffer() throws Exception {
        try (var h = new Harness()) {
            var data = ByteBuffer.allocate(100);
            for (int i = 0; i < 100; i++) data.put((byte) i);
            data.flip();

            h.loop.stageWrite(0, data);
            h.loop.flush();

            var out = h.readExactly(100);
            for (int i = 0; i < 100; i++) {
                assertEquals((byte) i, out[i], "byte " + i);
            }
        }
    }

    @Test
    void messageBiggerThanSlot_writesDirectly() throws Exception {
        // A single 50 KiB message is larger than the 8 KiB slot buffer.
        // Before the fix, stageWrite would throw BufferOverflowException
        // inside buf.put(data). After the fix it must deliver all bytes
        // (bypassing the slot buffer for oversized payloads).
        try (var h = new Harness()) {
            int size = 50_000;
            var data = ByteBuffer.allocate(size);
            for (int i = 0; i < size; i++) data.put((byte) (i % 251));
            data.flip();

            h.loop.stageWrite(0, data);
            h.loop.flush();

            var out = h.readExactly(size);
            for (int i = 0; i < size; i++) {
                assertEquals((byte) (i % 251), out[i], "byte " + i);
            }
        }
    }

    @Test
    void tenTimesFiveKiB_allBytesArriveInOrder() throws Exception {
        // Each 5 KiB message fits in the slot buffer on its own, but two
        // of them (10 KiB) don't — this exercises the "buffer would overflow
        // → flush first" branch inside stageWrite.
        try (var h = new Harness()) {
            int msgSize = 5_000;
            int count = 10;

            for (int m = 0; m < count; m++) {
                var data = ByteBuffer.allocate(msgSize);
                for (int i = 0; i < msgSize; i++) {
                    data.put((byte) ((m << 4) | (i & 0x0F)));
                }
                data.flip();
                h.loop.stageWrite(0, data);
            }
            h.loop.flush();

            var out = h.readExactly(msgSize * count);
            for (int m = 0; m < count; m++) {
                for (int i = 0; i < msgSize; i++) {
                    assertEquals((byte) ((m << 4) | (i & 0x0F)),
                            out[m * msgSize + i],
                            "msg " + m + " byte " + i);
                }
            }
        }
    }

    @Test
    void stageWrite_flushPath_preservesByteOrder() throws Exception {
        // Fill the slot buffer with one 7000-byte message, then stage
        // another 7000-byte message. The second stage must flush the first
        // synchronously (inside stageWrite) and then hold the second.
        // Byte order across the boundary must be preserved.
        try (var h = new Harness()) {
            int size = 7_000;

            var a = ByteBuffer.allocate(size);
            for (int i = 0; i < size; i++) a.put((byte) 0xAA);
            a.flip();
            h.loop.stageWrite(0, a);

            var b = ByteBuffer.allocate(size);
            for (int i = 0; i < size; i++) b.put((byte) 0xBB);
            b.flip();
            h.loop.stageWrite(0, b); // must trigger overflow-then-flush path

            h.loop.flush();
            var out = h.readExactly(size * 2);

            for (int i = 0; i < size; i++) assertEquals((byte) 0xAA, out[i], "A byte " + i);
            for (int i = 0; i < size; i++) assertEquals((byte) 0xBB, out[size + i], "B byte " + i);
        }
    }

    @Test
    void stageWriteAndFlush_blocksUntilFlushed() throws Exception {
        try (var h = new Harness()) {
            var data = ByteBuffer.allocate(256);
            for (int i = 0; i < 256; i++) data.put((byte) i);
            data.flip();

            h.loop.stageWriteAndFlush(0, data);
            // By contract, when this returns the bytes are on the socket —
            // the read-back must complete immediately.
            var out = h.readExactly(256);
            for (int i = 0; i < 256; i++) assertEquals((byte) i, out[i]);
        }
    }
}
