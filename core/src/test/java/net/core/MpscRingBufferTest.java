package net.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class MpscRingBufferTest {

    @Test
    void offer_poll_singleItem() {
        var buf = new MpscRingBuffer(16);
        assertTrue(buf.offer(42L, 1L));
        var cmd = buf.poll();
        assertNotNull(cmd);
        assertEquals(42L, cmd[0]);
        assertEquals(1L, cmd[1]);
    }

    @Test
    void poll_emptyReturnsNull() {
        var buf = new MpscRingBuffer(16);
        assertNull(buf.poll());
    }

    @Test
    void offer_poll_multipleItems() {
        var buf = new MpscRingBuffer(16);
        buf.offer(1L, 10L);
        buf.offer(2L, 20L);
        buf.offer(3L, 30L);

        var c1 = buf.poll();
        assertEquals(1L, c1[0]);
        var c2 = buf.poll();
        assertEquals(2L, c2[0]);
        var c3 = buf.poll();
        assertEquals(3L, c3[0]);
        assertNull(buf.poll());
    }

    @Test
    void offer_fullBufferReturnsFalse() {
        var buf = new MpscRingBuffer(4);
        assertTrue(buf.offer(1L, 1L));
        assertTrue(buf.offer(2L, 2L));
        assertTrue(buf.offer(3L, 3L));
        assertTrue(buf.offer(4L, 4L));
        assertFalse(buf.offer(5L, 5L)); // full
    }

    @Test
    void wrapsAround() {
        var buf = new MpscRingBuffer(4);
        // Fill and drain
        buf.offer(1L, 1L);
        buf.offer(2L, 2L);
        buf.poll();
        buf.poll();
        // Now add more (wraps around)
        buf.offer(3L, 3L);
        buf.offer(4L, 4L);
        var c = buf.poll();
        assertEquals(3L, c[0]);
        c = buf.poll();
        assertEquals(4L, c[0]);
    }

    @Test
    void multipleProducers_singleConsumer() throws Exception {
        var buf = new MpscRingBuffer(1024);
        int producerCount = 4;
        int messagesPerProducer = 200;
        var latch = new CountDownLatch(producerCount);
        var produced = new AtomicInteger(0);

        for (int p = 0; p < producerCount; p++) {
            final int pid = p;
            new Thread(() -> {
                for (int i = 0; i < messagesPerProducer; i++) {
                    while (!buf.offer(pid, i)) {
                        Thread.onSpinWait();
                    }
                    produced.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        int consumed = 0;
        while (consumed < producerCount * messagesPerProducer) {
            var cmd = buf.poll();
            if (cmd != null) {
                consumed++;
            } else {
                Thread.onSpinWait();
            }
        }

        latch.await();
        assertEquals(producerCount * messagesPerProducer, consumed);
        assertEquals(consumed, produced.get());
    }

    @Test
    void size_tracksCorrectly() {
        var buf = new MpscRingBuffer(16);
        assertEquals(0, buf.size());
        buf.offer(1L, 1L);
        assertEquals(1, buf.size());
        buf.offer(2L, 2L);
        assertEquals(2, buf.size());
        buf.poll();
        assertEquals(1, buf.size());
    }

    @Test
    void drainTo_consumesAll() {
        var buf = new MpscRingBuffer(16);
        buf.offer(1L, 10L);
        buf.offer(2L, 20L);
        buf.offer(3L, 30L);

        var sum = new long[]{0};
        int drained = buf.drainTo((a, b) -> sum[0] += a + b);
        assertEquals(3, drained);
        assertEquals(1 + 10 + 2 + 20 + 3 + 30, sum[0]);
        assertNull(buf.poll());
    }
}
