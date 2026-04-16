package jtroop.session;

import java.util.function.Consumer;

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
            throw new IllegalStateException("SessionStore full (capacity=" + capacity + ")");
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
        // Preserve the active bit, rewrite only the low 32 state bits.
        stateAndActive[index] = (stateAndActive[index] & ~STATE_MASK) | (state & STATE_MASK);
    }

    public synchronized int getState(ConnectionId id) {
        return (int) (stateAndActive[id.index()] & STATE_MASK);
    }

    public synchronized void setLastActivity(ConnectionId id, long nanos) {
        lastActivity[id.index()] = nanos;
    }

    public synchronized long getLastActivity(ConnectionId id) {
        return lastActivity[id.index()];
    }

    public synchronized int activeCount() {
        return count;
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
}
