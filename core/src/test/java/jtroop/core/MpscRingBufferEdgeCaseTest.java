package jtroop.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case and error-path tests for {@link MpscRingBuffer}.
 * Covers: capacity-1, drainTo on empty, EMPTY sentinel collision,
 * wraparound after full/drain cycles, and size consistency.
 */
@Timeout(5)
class MpscRingBufferEdgeCaseTest {

    @Test
    void capacity1_offerPollWorks() {
        // Power-of-2 rounding: capacity=1 rounds to... let's see
        var buf = new MpscRingBuffer(1);
        assertTrue(buf.offer(42L, 99L));
        var cmd = buf.poll();
        assertNotNull(cmd);
        assertEquals(42L, cmd[0]);
        assertEquals(99L, cmd[1]);
        assertNull(buf.poll());
    }

    @Test
    void capacity2_fillAndDrain() {
        var buf = new MpscRingBuffer(2);
        assertTrue(buf.offer(1L, 10L));
        assertTrue(buf.offer(2L, 20L));
        assertFalse(buf.offer(3L, 30L)); // full

        assertEquals(2, buf.size());
        var c1 = buf.poll();
        assertEquals(1L, c1[0]);
        var c2 = buf.poll();
        assertEquals(2L, c2[0]);
        assertNull(buf.poll());
        assertEquals(0, buf.size());

        // Can fill again after draining
        assertTrue(buf.offer(4L, 40L));
        assertTrue(buf.offer(5L, 50L));
        assertFalse(buf.offer(6L, 60L));
    }

    @Test
    void drainTo_emptyBuffer_returnsZero() {
        var buf = new MpscRingBuffer(16);
        int drained = buf.drainTo((a, b) -> fail("should not be called"));
        assertEquals(0, drained);
    }

    @Test
    void drainTo_singleItem() {
        var buf = new MpscRingBuffer(16);
        buf.offer(7L, 8L);
        var seen = new long[2];
        int drained = buf.drainTo((a, b) -> { seen[0] = a; seen[1] = b; });
        assertEquals(1, drained);
        assertEquals(7L, seen[0]);
        assertEquals(8L, seen[1]);
    }

    @Test
    void emptyMarker_collision_longMinValueInSlotB() {
        // Long.MIN_VALUE is the EMPTY sentinel for slotA. Verify that
        // storing Long.MIN_VALUE in slotB (the payload) does not confuse
        // the poll logic.
        var buf = new MpscRingBuffer(4);
        // Use a normal value for slotA (not EMPTY) and MIN_VALUE for slotB
        assertTrue(buf.offer(1L, Long.MIN_VALUE));
        var cmd = buf.poll();
        assertNotNull(cmd);
        assertEquals(1L, cmd[0]);
        assertEquals(Long.MIN_VALUE, cmd[1]);
    }

    @Test
    void repeatedFillDrain_manyWraparounds() {
        var buf = new MpscRingBuffer(4);
        for (int cycle = 0; cycle < 100; cycle++) {
            for (int i = 0; i < 4; i++) {
                assertTrue(buf.offer(cycle * 4L + i, i));
            }
            assertFalse(buf.offer(999L, 999L)); // full
            for (int i = 0; i < 4; i++) {
                var cmd = buf.poll();
                assertNotNull(cmd, "cycle=" + cycle + " i=" + i);
                assertEquals(cycle * 4L + i, cmd[0]);
            }
            assertNull(buf.poll());
            assertEquals(0, buf.size());
        }
    }

    @Test
    void size_afterOffersAndPolls() {
        var buf = new MpscRingBuffer(8);
        assertEquals(0, buf.size());
        buf.offer(1L, 1L);
        buf.offer(2L, 2L);
        buf.offer(3L, 3L);
        assertEquals(3, buf.size());
        buf.poll();
        assertEquals(2, buf.size());
        buf.poll();
        buf.poll();
        assertEquals(0, buf.size());
    }

    @Test
    void drainTo_afterPartialPoll() {
        var buf = new MpscRingBuffer(8);
        buf.offer(1L, 10L);
        buf.offer(2L, 20L);
        buf.offer(3L, 30L);
        buf.poll(); // consume first

        var sum = new long[]{0};
        int drained = buf.drainTo((a, b) -> sum[0] += a);
        assertEquals(2, drained);
        assertEquals(2L + 3L, sum[0]);
    }

    @Test
    void poll_returnsSameArrayInstance() {
        // The MpscRingBuffer reuses a single long[2] for poll results
        var buf = new MpscRingBuffer(8);
        buf.offer(1L, 10L);
        buf.offer(2L, 20L);
        var r1 = buf.poll();
        var r2 = buf.poll();
        assertSame(r1, r2, "poll() should reuse the same result array");
    }
}
