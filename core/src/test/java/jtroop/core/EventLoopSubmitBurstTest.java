package jtroop.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fix 2 regression tests. The MPSC setup ring in EventLoop was previously
 * backed by an unbounded ConcurrentLinkedQueue overflow ({@code setupOverflow})
 * that allocated one CLQ.Node per submit once the 4096-slot ring filled. Under
 * a producer burst (e.g. a malicious client flooding connect/close) this
 * violated the zero-allocation contract and gave an attacker an unbounded
 * heap-growth vector.
 *
 * <p>Post-fix: external producers that hit a full ring block (bounded
 * spin-then-parkNanos) until the consumer frees a slot — zero heap allocation
 * on the producer thread, correctness preserved (no task dropped). Re-entrant
 * same-loop submits that exceed the ring throw immediately since that
 * indicates a single task enqueuing more than 4096 followup tasks, a design
 * error rather than back-pressure.
 */
@Timeout(15)
class EventLoopSubmitBurstTest {

    /**
     * Under a paused consumer, a single producer thread bursts past the ring
     * capacity and then lets the consumer drain. Expected outcomes:
     *   1. every task eventually runs (correctness),
     *   2. the producer thread's own allocation stays bounded by a small
     *      constant regardless of burst size (zero-alloc).
     *
     * Pre-fix: CLQ nodes accumulated on the producer thread — allocation
     * scaled linearly with the spill (~32 B/submit beyond the 4096-slot ring).
     * A 5000-submit spill therefore allocated roughly 160 KB on the producer.
     */
    @Test
    void submitBurst_beyondRingCapacity_zeroAllocationOnProducerThread() throws Exception {
        var loop = new EventLoop("burst-test");
        var released = new CountDownLatch(1);
        var blockerRunning = new CountDownLatch(1);

        // Block the consumer until we say so — forces the ring to fill.
        loop.submit(() -> {
            blockerRunning.countDown();
            try { released.await(); } catch (InterruptedException _) {}
        });
        loop.start();
        blockerRunning.await();

        // The noop task is a constant lambda — submitting it does not itself
        // allocate anything on the caller side, so whatever allocation we
        // measure came from submit().
        final Runnable noop = () -> {};
        final int ringCapacity = 4096;
        final int burstSize = ringCapacity + 5000;

        var total = new CountDownLatch(burstSize);
        final Runnable counted = total::countDown;

        var error = new AtomicReference<Throwable>();
        var allocDelta = new long[1];

        Thread producer = new Thread(() -> {
            try {
                var tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                // Warmup one call so JMX internals have amortised their init
                // allocations and not taint the measurement.
                loop.submit(noop);
                long before = tmx.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < burstSize; i++) {
                    loop.submit(counted);
                }
                long after = tmx.getCurrentThreadAllocatedBytes();
                allocDelta[0] = after - before;
            } catch (Throwable t) {
                error.set(t);
            }
        }, "burst-producer");

        producer.setDaemon(true);
        producer.start();

        // Give the producer a moment to fill the ring and start blocking.
        Thread.sleep(50);
        // Now release the consumer so it drains the ring and wakes the producer.
        released.countDown();

        producer.join(10_000);
        assertNull(error.get(), "producer thread threw: " + error.get());
        assertTrue(total.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "not all tasks ran post-drain");

        // Pre-fix: ~5000 × ~32 B CLQ nodes = ~160 KB on the producer thread.
        // Post-fix: no allocation in the submit() path itself; only JMX /
        // thread-local housekeeping noise (~few KB across a 9000-submit
        // burst). 10 KB envelope distinguishes the fixed-vs-broken cases
        // comfortably while tolerating JMX measurement noise.
        assertTrue(allocDelta[0] < 10_240,
                "producer thread allocated " + allocDelta[0] +
                " bytes for a " + burstSize + "-task burst — expected < 10 KB. " +
                "If this fails on main, the setupOverflow CLQ spill is still active " +
                "(would allocate ~160 KB here).");

        loop.close();
    }

    /**
     * Re-entrant submits from the loop thread that exceed the main ring
     * must not block (would deadlock the sole drainer) and must not
     * silently drop. Post-fix: overflow lands in the growable self-submit
     * queue (ArrayDeque) so every submit succeeds and every task runs,
     * regardless of how many the current task enqueues.
     */
    @Test
    void submitFromLoopThread_beyondRingCapacity_allTasksRun() throws Exception {
        var loop = new EventLoop("reentrant-test");
        var error = new AtomicReference<Throwable>();
        var allRun = new CountDownLatch(5000);

        loop.submit(() -> {
            try {
                for (int i = 0; i < 5000; i++) {
                    loop.submit(allRun::countDown);
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });
        loop.start();
        assertTrue(allRun.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "every re-entrantly-submitted task must eventually run");
        assertNull(error.get(),
                "re-entrant submit must not throw under overflow: " + error.get());
        loop.close();
    }
}
