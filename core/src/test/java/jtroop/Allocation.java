package jtroop;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/**
 * Unit-test harness for asserting zero-allocation properties on hot paths.
 *
 * <p>Wraps {@link ThreadMXBean#getThreadAllocatedBytes(long)} to measure how
 * many bytes the current thread allocated during a given code block. Tests
 * that want to pin a method's allocation-free contract can assert the delta
 * falls under a small noise budget (a few hundred bytes, accounting for JMX
 * bookkeeping and JIT compilation-log housekeeping that can't be avoided).
 *
 * <p>Usage:
 * <pre>{@code
 * @Test
 * void codecRoundtrip_zeroAlloc() {
 *     var codec = ...;
 *     var msg = new PositionUpdate(0f, 0f, 0f);
 *     // Warm up JIT + Thread-local caches.
 *     for (int i = 0; i < 10_000; i++) codec.encodeAndDecode(msg);
 *     long delta = Allocation.measure(() -> {
 *         for (int i = 0; i < 1_000; i++) codec.encodeAndDecode(msg);
 *     });
 *     Allocation.assertNearZero(delta, 1_024, "codecRoundtrip");
 * }
 * }</pre>
 *
 * <p>The 1 KB noise envelope is a safe default — in practice zero-alloc hot
 * paths measure 0 allocated bytes across thousands of iterations, and the
 * small fixed cost we allow covers JMX sampling overhead.
 */
public final class Allocation {

    private static final ThreadMXBean TMX =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Allocation() {}

    /**
     * Run {@code task} and return the number of bytes allocated on the
     * current thread during its execution.
     */
    public static long measure(Runnable task) {
        long before = TMX.getCurrentThreadAllocatedBytes();
        try {
            task.run();
        } finally {
            // Read after — if the task threw, delta is still meaningful.
        }
        long after = TMX.getCurrentThreadAllocatedBytes();
        return after - before;
    }

    /**
     * Assert that {@code actualBytes} stays within {@code budgetBytes}. On
     * failure, throws {@link AssertionError} naming the site so debugging
     * is easy.
     *
     * <p>A small per-call JMX overhead is unavoidable, so a budget of 1 KB
     * is a reasonable starting point for "zero-alloc" hot paths.
     */
    public static void assertNearZero(long actualBytes, long budgetBytes, String site) {
        if (actualBytes > budgetBytes) {
            throw new AssertionError(
                    "expected " + site + " to allocate < " + budgetBytes +
                    " bytes; actually allocated " + actualBytes);
        }
    }

    /** Convenience overload with a 1 KB default budget. */
    public static void assertNearZero(long actualBytes, String site) {
        assertNearZero(actualBytes, 1_024, site);
    }
}
