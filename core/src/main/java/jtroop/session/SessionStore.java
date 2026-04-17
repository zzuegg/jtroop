package jtroop.session;

import jtroop.ConnectionException;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * SoA (Struct-of-Arrays) session state storage.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code long[] stateAndActive} — upper bit (63) is the active flag,
 *       lower 32 bits hold the user-visible state. Packing active + state into
 *       a single primitive array keeps the hot {@code forEachActive} scan on
 *       one cache line per slot instead of two.</li>
 *   <li>{@code int[] generations} — generation counter; incremented on each
 *       allocate so stale {@link ConnectionId} handles are detected.</li>
 *   <li>{@code long[] lastActivity} — last-activity timestamp in nanos.</li>
 *   <li>{@code int[] nextFree} — dedicated free-list pointer per slot. Using
 *       a separate array (instead of overloading {@code states}) means
 *       {@link #setState} and {@link #getState} no longer collide with the
 *       free-list bookkeeping.</li>
 * </ul>
 *
 * <p>All arrays are primitive — see CLAUDE.md rule #1. No per-call allocations
 * on the hot {@code isActive} / {@code setState} / {@code forEachActive} paths
 * beyond the {@link ConnectionId} record constructed inside the loop body
 * (expected to be scalar-replaced by C2 when the consumer callsite inlines).
 */
public final class SessionStore {

    // Packed layout for stateAndActive[i]:
    //   bit 63        = active flag
    //   bits 0..31    = user state (int)
    //   bits 32..62   = reserved (must be zero)
    private static final long ACTIVE_BIT = 1L << 63;
    private static final long STATE_MASK = 0xFFFFFFFFL;

    private static final int END_OF_FREE = -1;

    private final int capacity;
    private final int[] generations;
    private final long[] stateAndActive;
    private final long[] lastActivity;
    private final int[] nextFree;
    private int count;
    private int freeHead;

    public SessionStore(int capacity) {
        this.capacity = capacity;
        this.generations = new int[capacity];
        this.stateAndActive = new long[capacity];
        this.lastActivity = new long[capacity];
        this.nextFree = new int[capacity];
        this.count = 0;
        // Build free list
        for (int i = 0; i < capacity - 1; i++) {
            nextFree[i] = i + 1;
        }
        if (capacity > 0) {
            nextFree[capacity - 1] = END_OF_FREE;
        }
        freeHead = capacity == 0 ? END_OF_FREE : 0;
    }

    public synchronized ConnectionId allocate() {
        if (freeHead == END_OF_FREE) {
            throw new ConnectionException("SessionStore full (capacity=" + capacity + ")");
        }
        int index = freeHead;
        freeHead = nextFree[index];
        generations[index]++;
        stateAndActive[index] = ACTIVE_BIT; // active=1, state=0
        lastActivity[index] = 0;
        count++;
        return ConnectionId.of(index, generations[index]);
    }

    public synchronized void release(ConnectionId id) {
        int index = id.index();
        if (index < 0 || index >= capacity) return;
        if ((stateAndActive[index] & ACTIVE_BIT) == 0L) return; // already released — no-op
        if (generations[index] != id.generation()) return;     // stale handle — no-op
        stateAndActive[index] = 0L; // clear active + state
        nextFree[index] = freeHead;
        freeHead = index;
        count--;
    }

    public synchronized boolean isActive(ConnectionId id) {
        int index = id.index();
        if (index < 0 || index >= capacity) return false;
        return (stateAndActive[index] & ACTIVE_BIT) != 0L
                && generations[index] == id.generation();
    }

    public synchronized void setState(ConnectionId id, int state) {
        int index = id.index();
        if (index < 0 || index >= capacity) return;
        if (generations[index] != id.generation()) return; // stale handle
        if ((stateAndActive[index] & ACTIVE_BIT) == 0L) return; // not active
        // Preserve the active bit, rewrite only the low 32 state bits.
        stateAndActive[index] = (stateAndActive[index] & ~STATE_MASK) | (state & STATE_MASK);
    }

    public synchronized int getState(ConnectionId id) {
        int index = id.index();
        if (index < 0 || index >= capacity) return 0;
        if (generations[index] != id.generation()) return 0; // stale handle
        return (int) (stateAndActive[index] & STATE_MASK);
    }

    public synchronized void setLastActivity(ConnectionId id, long nanos) {
        int index = id.index();
        if (index < 0 || index >= capacity) return;
        if (generations[index] != id.generation()) return; // stale handle
        lastActivity[index] = nanos;
    }

    public synchronized long getLastActivity(ConnectionId id) {
        int index = id.index();
        if (index < 0 || index >= capacity) return 0L;
        if (generations[index] != id.generation()) return 0L; // stale handle
        return lastActivity[index];
    }

    public synchronized int activeCount() {
        return count;
    }

    /**
     * Maximum number of concurrent sessions this store can hold. Fixed at
     * construction; useful for sizing parallel per-slot arrays (e.g. a
     * {@code SocketChannel[]} indexed by session slot for zero-lookup
     * broadcast fan-out — see {@code Server.broadcastImpl}).
     */
    public int capacity() {
        return capacity;
    }

    public synchronized void forEachActive(Consumer<ConnectionId> consumer) {
        // Index loop + primitive-array reads. The ConnectionId record is
        // scalar-replaced by C2 at monomorphic callsites (CLAUDE.md rule #2).
        final long[] sa = stateAndActive;
        final int[] gens = generations;
        final int cap = capacity;
        for (int i = 0; i < cap; i++) {
            if ((sa[i] & ACTIVE_BIT) != 0L) {
                consumer.accept(ConnectionId.of(i, gens[i]));
            }
        }
    }

    /**
     * Iterate active connections as packed {@code long} ids (no
     * {@link ConnectionId} record construction). The long encodes
     * {@code (generation << 32) | (index & 0xFFFFFFFFL)} — the exact same
     * layout as {@link ConnectionId#id()}.
     *
     * <p>This is the fastest iteration path: when the {@link LongConsumer}
     * callsite is monomorphic (e.g. a cached field-captured lambda) the
     * entire loop body collapses to a primitive scan with no heap traffic,
     * independent of C2's ability to scalar-replace the {@link ConnectionId}
     * record. Use this for broadcast fan-out, metrics sweeps, and connection
     * GC sweeps on hot paths.
     *
     * @param consumer receives each active id as a packed long; reconstruct
     *                 a {@link ConnectionId} via {@code new ConnectionId(id)}
     *                 only if needed downstream.
     */
    public synchronized void forEachActiveLong(LongConsumer consumer) {
        final long[] sa = stateAndActive;
        final int[] gens = generations;
        final int cap = capacity;
        for (int i = 0; i < cap; i++) {
            if ((sa[i] & ACTIVE_BIT) != 0L) {
                // Packed form matches ConnectionId.of(index, generation).
                consumer.accept(((long) gens[i] << 32) | (i & 0xFFFFFFFFL));
            }
        }
    }

    /**
     * Primitive-spec iteration for allocation-sensitive fan-out paths.
     *
     * <p>Yields {@code (index, generation)} as primitives directly — no
     * {@link ConnectionId} record is constructed here, so there is nothing
     * to scalar-replace at the iteration boundary. Server broadcast uses
     * this to walk all connected clients with zero per-iteration allocation.
     *
     * <p>The {@link IndexVisitor} is typically a final class held as a
     * server field, so the call site is monomorphic and C2 inlines the
     * visit body into the scan loop (CLAUDE.md rule #4).
     */
    public synchronized void forEachActiveIndex(IndexVisitor visitor) {
        final long[] sa = stateAndActive;
        final int[] gens = generations;
        final int cap = capacity;
        for (int i = 0; i < cap; i++) {
            if ((sa[i] & ACTIVE_BIT) != 0L) {
                visitor.visit(i, gens[i]);
            }
        }
    }

    /**
     * Snapshot active ids into a caller-owned {@code long[]}. Returns the
     * number of ids written. No allocation when {@code out.length >=
     * activeCount()}.
     *
     * <p>Useful for callers that need to iterate active connections without
     * holding this store's monitor during the callback (e.g. to allow
     * re-entrant mutations via a deferred-command buffer).
     *
     * @param out destination buffer; must be sized to {@code activeCount()}
     *            or larger. If smaller, the method writes up to
     *            {@code out.length} entries and returns that count.
     * @return number of active ids written
     */
    public synchronized int activeCopyIds(long[] out) {
        final long[] sa = stateAndActive;
        final int[] gens = generations;
        final int cap = capacity;
        final int max = out.length;
        int n = 0;
        for (int i = 0; i < cap && n < max; i++) {
            if ((sa[i] & ACTIVE_BIT) != 0L) {
                out[n++] = ((long) gens[i] << 32) | (i & 0xFFFFFFFFL);
            }
        }
        return n;
    }

    /** Primitive visitor used by {@link #forEachActiveIndex}. */
    @FunctionalInterface
    public interface IndexVisitor {
        void visit(int index, int generation);
    }
}
