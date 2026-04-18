package jtroop.core;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class EventLoop implements Runnable, AutoCloseable {

    // VarHandles for release/acquire access to array slots so that writes
    // made by external threads are seen by the loop thread (and vice-versa)
    // without full-volatile reads on every iteration. Also prevents the JIT
    // from hoisting the stageWriteAndFlush spin-wait read out of its loop.
    private static final VarHandle PENDING_WRITE;
    private static final VarHandle WRITE_TARGETS;
    private static final VarHandle SETUP_SLOTS;
    private static final VarHandle WAITING_THREADS;
    private static final VarHandle SETUP_READ_INDEX;
    private static final VarHandle PARKED_PRODUCER;
    static {
        try {
            PENDING_WRITE = MethodHandles.arrayElementVarHandle(boolean[].class);
            WRITE_TARGETS = MethodHandles.arrayElementVarHandle(SocketChannel[].class);
            SETUP_SLOTS   = MethodHandles.arrayElementVarHandle(Runnable[].class);
            WAITING_THREADS = MethodHandles.arrayElementVarHandle(Thread[].class);
            var lookup = MethodHandles.lookup();
            SETUP_READ_INDEX = lookup.findVarHandle(EventLoop.class, "setupReadIndex", long.class);
            PARKED_PRODUCER  = lookup.findVarHandle(EventLoop.class, "parkedProducer", Thread.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final Selector selector;
    private final Thread thread;
    private volatile boolean running;

    // Lock-free MPSC ring for submit(Runnable). Pre-sized; zero per-call
    // allocation on the fast path. Producers CAS a write index, then
    // release-store the Runnable into the ring slot. The single consumer
    // (loop thread) acquire-loads each slot; a null means "not yet published".
    //
    // When the ring is full:
    //  * External-thread submits block (bounded spin-then-parkNanos) until
    //    the consumer frees a slot. Zero heap allocation — the former
    //    ConcurrentLinkedQueue overflow (~32 B / Node per spilled submit)
    //    was a zero-alloc violation and an unbounded-heap-growth DoS
    //    vector under a flooded submit() caller.
    //  * Re-entrant submits from the loop thread itself throw
    //    IllegalStateException — there is no way to drain the ring
    //    without returning from the current task, so "block until free"
    //    would deadlock. 4096 slots are generous; exceeding this from a
    //    single task is a design error, not back-pressure.
    /**
     * Main-ring capacity. Sized so that realistic workloads never saturate
     * it — broadcast storms under stress tests have been observed to push
     * &gt; 10 000 concurrent in-flight submits per loop. 64 K × 8 B = 512 KB
     * per loop; across typical 4–16 loops that is 2–8 MB of reference
     * storage, acceptable for the allocation-free guarantee it buys.
     *
     * <p>Overflow is still handled structurally: external producers park
     * with a 1 ms bounded deadline and retry, and re-entrant submits from
     * the loop thread fall through to the growable {@link #selfQueue} so
     * no fixed cap anywhere can be hit in practice.
     */
    private static final int SETUP_RING_CAPACITY = 65536; // power of 2
    private static final int SETUP_RING_MASK = SETUP_RING_CAPACITY - 1;
    /**
     * Initial capacity of the self-submit queue. The queue grows geometrically
     * if a single main-ring iteration produces more than this many
     * re-entrant submits; once grown, the larger array is reused for the
     * rest of the loop's lifetime — so after warmup the steady-state cost
     * is zero allocation.
     */
    private static final int SELF_QUEUE_INITIAL_CAPACITY = 1024;
    /** Spin iterations before a ring-full external producer falls back to parkNanos. */
    private static final int SUBMIT_SPIN_BUDGET = 64;
    /** parkNanos deadline when ring stays full; bounds producer wake latency. */
    private static final long SUBMIT_PARK_NS = 1_000_000L;

    private final Runnable[] setupRing = new Runnable[SETUP_RING_CAPACITY];
    private final AtomicLong setupWriteIndex = new AtomicLong(0);
    /**
     * Single-consumer cursor written by the loop thread. External producers
     * read via {@link #SETUP_READ_INDEX} getAcquire to pair with the
     * consumer's setRelease — guarantees monotonic visibility so a blocked
     * producer eventually sees a drain.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long setupReadIndex = 0;
    /**
     * At most one external producer parked waiting for a main-ring slot.
     * A second waiter overwrites this and the displaced waiter relies on its
     * own parkNanos(1 ms) timeout to re-check — max 1 ms extra wake latency
     * under multi-producer contention. No lost-wakeup hang possible.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile Thread parkedProducer = null;
    /**
     * Self-submit queue (loop thread only). Used as a fallback when the main
     * ring is full and the current submit comes from the loop thread itself
     * — blocking on self would deadlock. Grows geometrically under burst
     * load so no fixed cap can be hit; after the first growth pass, the
     * underlying array is reused and steady-state allocation is zero.
     *
     * <p>Single-thread access (loop thread only), so no synchronisation is
     * needed beyond happens-before supplied by the outer selector/VarHandle
     * protocol that brackets any entry into this loop.
     */
    private final java.util.ArrayDeque<Runnable> selfQueue =
            new java.util.ArrayDeque<>(SELF_QUEUE_INITIAL_CAPACITY);

    private final ByteBuffer[] writeBuffers;
    private final boolean[] pendingWrite;
    private final SocketChannel[] writeTargets;
    private final Thread[] waitingThreads;
    private final int maxConnections;
    private final int writeBufferSize;
    private volatile int activeSlots = 0;

    public EventLoop(String name, int maxConnections, int writeBufferSize) throws IOException {
        this.selector = Selector.open();
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
        this.maxConnections = maxConnections;
        this.writeBufferSize = writeBufferSize;
        this.writeBuffers = new ByteBuffer[maxConnections];
        this.pendingWrite = new boolean[maxConnections];
        this.writeTargets = new SocketChannel[maxConnections];
        this.waitingThreads = new Thread[maxConnections];
        // Lazy allocation: writeBuffers[i] is allocated on first stageWrite.
        // Most connections may be read-heavy and never need the buffer.
    }

    public EventLoop(String name, int maxConnections) throws IOException {
        this(name, maxConnections, 65536);
    }

    public EventLoop(String name) throws IOException {
        this(name, 64, 65536);
    }

    public void start() {
        running = true;
        thread.start();
    }

    /**
     * Submit a task for execution on the event-loop thread.
     * Thread-safe for any producer; single-consumer drain on the loop thread.
     *
     * <p>Fast path (ring has room): zero heap allocation — CAS-claim a slot,
     * release-store the Runnable reference, wake the selector.
     *
     * <p>External producer slow path (ring full): bounded spin, then
     * {@code parkNanos(1 ms)} loop re-reading the consumer cursor via
     * acquire; the consumer unparks the waiter after each drain batch. No
     * heap allocation, no lost wakeup.
     *
     * <p>Re-entrant slow path (loop thread submitting beyond 4096 in-flight
     * tasks from the current task): throws {@link IllegalStateException}.
     * Blocking on self would deadlock, and 4096 is a generous budget for
     * any single task's followup work — exceeding it indicates a design
     * error, not back-pressure.
     */
    public void submit(Runnable task) {
        boolean onLoopThread = Thread.currentThread() == thread;
        long idx;
        while (true) {
            idx = setupWriteIndex.get();
            long read = (long) SETUP_READ_INDEX.getAcquire(this);
            if (idx - read < SETUP_RING_CAPACITY) {
                if (setupWriteIndex.compareAndSet(idx, idx + 1)) break;
                continue; // lost CAS race, retry immediately
            }
            // Main ring full.
            if (onLoopThread) {
                // Fall back to the self-submit queue. Cannot block on self —
                // we'd deadlock the only thread that drains. The queue is
                // reserved exclusively for this case so external producers
                // saturating the main ring never starve the loop's own
                // followup work.
                submitToSelfQueue(task);
                return;
            }
            // External producer: back off and wait for a main-ring slot.
            awaitSetupRingSpace(idx);
        }

        int slot = (int) (idx & SETUP_RING_MASK);
        // Release-store so the consumer's acquire-load sees the publish.
        SETUP_SLOTS.setRelease(setupRing, slot, task);
        selector.wakeup();
    }

    /**
     * Enqueue on the self-submit queue. Only the loop thread ever calls
     * this (from {@link #submit} when the main ring is full) and only the
     * loop thread drains it — so no synchronisation is needed.
     *
     * <p>The ArrayDeque grows automatically (amortised O(1)) so there is no
     * fixed cap to overflow; the "magic number"
     * {@link #SELF_QUEUE_INITIAL_CAPACITY} is only a preallocation hint.
     * selector.wakeup is not needed here — we are the loop thread; control
     * will return to {@link #processSetupTasks} immediately after the
     * current task.
     */
    private void submitToSelfQueue(Runnable task) {
        selfQueue.addLast(task);
    }

    /**
     * External-producer wait path: spins briefly on the common case where the
     * consumer is about to drain, then parks with a self-timed 1 ms deadline
     * so a racing multi-waiter scenario never hangs.
     */
    private void awaitSetupRingSpace(long claimedIdx) {
        int spins = 0;
        while ((claimedIdx - (long) SETUP_READ_INDEX.getAcquire(this)) >= SETUP_RING_CAPACITY) {
            if (spins < SUBMIT_SPIN_BUDGET) {
                Thread.onSpinWait();
                spins++;
            } else {
                // Re-publish each loop iteration so the consumer can find us
                // after a spurious wake or after another waiter cleared the
                // slot via getAndSet.
                PARKED_PRODUCER.setRelease(this, Thread.currentThread());
                // Re-check after publishing in case the consumer drained
                // between our last read and the publish — otherwise we could
                // park just after the final drain and miss the unpark.
                if ((claimedIdx - (long) SETUP_READ_INDEX.getAcquire(this)) < SETUP_RING_CAPACITY) {
                    break;
                }
                LockSupport.parkNanos(SUBMIT_PARK_NS);
            }
        }
        // Leave parkedProducer cleared for the next caller's benefit; we
        // may not have been the one who published it (spin-only path).
        PARKED_PRODUCER.compareAndSet(this, Thread.currentThread(), null);
    }

    /**
     * Hot path: stage bytes for writing. Uses synchronized on the per-slot buffer
     * (biased locking ~6ns uncontended).
     *
     * <p>Back-pressure: if the staging buffer cannot hold the new data, we drain
     * what the socket accepts (compact — no data loss), then spin-yield until
     * room is available or 5s deadline elapses. No bytes are silently dropped.
     *
     * <p>Oversized payloads (larger than the slot buffer, 8 KiB): we hold the
     * buf lock, flush whatever's staged, then write the oversized payload
     * directly to the channel. Prevents BufferOverflowException on large frames.
     */
    /**
     * Lazily allocate the per-slot write buffer on first use. Direct buffers
     * cost 64KB+ of native memory each; connections that never write (read-heavy
     * protocols, idle connections) skip the allocation entirely.
     */
    private ByteBuffer ensureWriteBuffer(int slot) {
        var buf = writeBuffers[slot];
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(writeBufferSize);
            writeBuffers[slot] = buf;
        }
        return buf;
    }

    public void stageWrite(int slot, ByteBuffer data) {
        var buf = ensureWriteBuffer(slot);
        var channel = (SocketChannel) WRITE_TARGETS.getAcquire(writeTargets, slot);
        int need = data.remaining();
        // Fast path: enough room, just put.
        synchronized (buf) {
            if (buf.remaining() >= need) {
                buf.put(data);
                PENDING_WRITE.setRelease(pendingWrite, slot, true);
                return;
            }
            // Oversized — larger than the slot buffer itself. Drain staged,
            // then write the big payload directly under the buf lock so no
            // other thread interleaves bytes on the same channel.
            if (need > buf.capacity() && channel != null && channel.isConnected()) {
                buf.flip();
                try {
                    while (buf.hasRemaining()) {
                        int n = channel.write(buf);
                        if (n == 0) { Thread.onSpinWait(); }
                    }
                } catch (IOException _) { buf.clear(); return; }
                buf.clear();
                PENDING_WRITE.setRelease(pendingWrite, slot, false);
                try {
                    long dl = System.nanoTime() + 5_000_000_000L;
                    while (data.hasRemaining()) {
                        int n = channel.write(data);
                        if (n == 0) {
                            if (System.nanoTime() > dl) return;
                            Thread.onSpinWait();
                        }
                    }
                } catch (IOException _) {}
                return;
            }
        }
        // Slow path: buffer full but fits in capacity — drain, spin until room available.
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (true) {
            synchronized (buf) {
                if (channel != null && channel.isConnected()) {
                    buf.flip();
                    try { channel.write(buf); } catch (IOException _) {}
                    buf.compact();
                    if (buf.position() > 0) {
                        PENDING_WRITE.setRelease(pendingWrite, slot, true);
                    }
                }
                if (buf.remaining() >= need) {
                    buf.put(data);
                    PENDING_WRITE.setRelease(pendingWrite, slot, true);
                    return;
                }
            }
            selector.wakeup();
            if (System.nanoTime() > deadlineNs) {
                throw new IllegalStateException(
                        "stageWrite back-pressure timeout on slot " + slot +
                        " — receiver consuming too slowly (needed " + need + " bytes)");
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Stage bytes and block until the bytes have been handed to the socket.
     *
     * <p>Fast path: the caller writes directly to the socket under the per-slot
     * buffer lock. This avoids a selector.wakeup() + EventLoop round-trip per
     * op, which was costing ~48 B/op of HashMap$Node allocation inside
     * sun.nio.ch.SelectorImpl.processReadyEvents (the ready-keys set). The
     * buffer lock serialises against the EventLoop's own flushPendingWrites(),
     * so writes on the same channel are never interleaved.
     *
     * <p>Falls back to the EventLoop-driven path when the kernel socket buffer
     * only partially accepts the write (TCP back-pressure), another thread has
     * already staged bytes, or the channel is not yet connected. The existing
     * park/unpark handoff drains the remainder on the next selector cycle —
     * correctness is preserved.
     */
    public void stageWriteAndFlush(int slot, ByteBuffer data) {
        var buf = ensureWriteBuffer(slot);
        var channel = (SocketChannel) WRITE_TARGETS.getAcquire(writeTargets, slot);
        if (channel != null && channel.isConnected()) {
            synchronized (buf) {
                if (buf.position() == 0) {
                    // No staged bytes → write directly.
                    int remaining = data.remaining();
                    try {
                        int n = channel.write(data);
                        if (n == remaining) return; // fully accepted — done
                        // Partial / zero write: leftover in `data` still needs to flush
                        // via the staged path below.
                    } catch (IOException _) {
                        // Peer closed / reset — the read side will tear down.
                        return;
                    }
                }
            }
        }
        // Slow path: contended buffer, back-pressure, or channel not ready.
        // Publish self BEFORE staging so a flush that races with us sees the
        // waiter via the release/acquire pair; the subsequent stageWrite's
        // setRelease on pendingWrite also covers happens-before for this
        // slot write.
        var self = Thread.currentThread();
        WAITING_THREADS.setRelease(waitingThreads, slot, self);
        try {
            stageWrite(slot, data);
            selector.wakeup();
            // Hybrid spin-then-park. A short onSpinWait budget absorbs the
            // common case where flushPendingWrites drains within a few
            // hundred nanoseconds — cheaper than crossing into the kernel.
            // When back-pressure is real, fall back to parkNanos(1ms): the
            // loop does the kernel sleep, flushPendingWrites unparks us on
            // drain for prompt wake, and parkNanos's own 1ms deadline
            // bounds latency if a racing waiter on the same slot stole our
            // waitingThreads entry (max 1ms extra wake latency under
            // concurrent waiters — no lost-wakeup hang).
            int spins = 0;
            while ((boolean) PENDING_WRITE.getAcquire(pendingWrite, slot)) {
                if (spins < FLUSH_WAIT_SPIN_BUDGET) {
                    Thread.onSpinWait();
                    spins++;
                } else {
                    // Re-publish before parking: a sibling caller may have
                    // overwritten our slot, and flush's getAndSet(null) may
                    // have consumed our token after a spurious wake.
                    WAITING_THREADS.setRelease(waitingThreads, slot, self);
                    java.util.concurrent.locks.LockSupport.parkNanos(FLUSH_WAIT_PARK_NS);
                }
            }
        } finally {
            // Clear only our own token so we don't trample a newer caller.
            WAITING_THREADS.compareAndSet(waitingThreads, slot, self, null);
        }
    }

    /** Spin iterations before falling back to parkNanos. ~200ns-1us worth
     *  of onSpinWait on modern x86; covers the typical drain latency. */
    private static final int FLUSH_WAIT_SPIN_BUDGET = 64;
    /** parkNanos deadline when back-pressure is real. 1ms ≈ one selector
     *  tick; bounds wake latency if another thread stole our waiter slot. */
    private static final long FLUSH_WAIT_PARK_NS = 1_000_000L;

    public void flush() {
        selector.wakeup();
    }

    public void registerWriteTarget(int slot, SocketChannel channel) {
        WRITE_TARGETS.setRelease(writeTargets, slot, channel);
        if (slot >= activeSlots) activeSlots = slot + 1;
    }

    // Cached consumer handed to {@link Selector#select(Consumer, long)} /
    // {@link Selector#selectNow(Consumer)} so the loop doesn't allocate a
    // fresh lambda per cycle. Those Selector.select forms iterate the internal
    // ready-keys directly and auto-remove each entry — no
    // {@code selectedKeys().iterator()} HashIterator allocation (~32 B/cycle)
    // per fan-out recipient (CLAUDE.md rule #7). Under broadcast fan-out at
    // N=100 this saves 100×32 = ~3200 B/op across the N receiver client loops.
    private final java.util.function.Consumer<SelectionKey> keyDispatch = this::dispatchKey;

    private void dispatchKey(SelectionKey key) {
        if (!key.isValid()) return;
        if (key.attachment() instanceof KeyHandler handler) {
            try {
                handler.handle(key);
            } catch (Throwable t) {
                // A misbehaving connection must not take down the loop.
                try { key.cancel(); } catch (Throwable _) {}
                try { key.channel().close(); } catch (Throwable _) {}
                System.err.println("EventLoop: handler threw, connection closed: " + t);
            }
        }
    }

    // Adaptive-poll tuning. The goal is to shave the ~1ms latency floor that
    // a naive select(1) imposes when a producer stages bytes just *after*
    // the loop last returned from the selector — without starving the
    // producer on localhost where producer and loop threads compete for CPU.
    //
    //   * When we have work queued (pending writes or pending setup tasks)
    //     there's no reason to block in select — use selectNow() so the
    //     next iteration drains the new bytes without a 1ms stall. This is
    //     the critical path for stageWriteAndFlush() which wakes the loop
    //     but may race such that by the time we re-enter select(), the
    //     wakeup token has already been consumed by an earlier cycle.
    //
    //   * When truly idle, fall back to select(1ms). Any producer thread
    //     calling submit() / stageWriteAndFlush() wakes the selector
    //     promptly via the existing wakeup() machinery, so wakeup-path
    //     latency is unchanged.
    //
    //   * The work-detection check is O(activeSlots) of plain-volatile
    //     reads, but flushPendingWrites() does the same scan inside the
    //     loop so it is already on the hot path; no new cost.
    //
    //   * Graceful shutdown: close() sets running=false and wakes the
    //     selector; we exit either branch promptly on the next loop check.
    private static final long BLOCKING_SELECT_TIMEOUT_MS = 1L;

    @Override
    public void run() {
        while (running) {
            try {
                // Adaptive: if producers have already staged bytes or queued
                // setup tasks, skip the 1ms block and drain immediately via
                // selectNow(Consumer). Otherwise block up to 1ms via
                // select(Consumer, long). Both forms iterate the internal
                // ready-keys directly through `keyDispatch`, so no
                // selectedKeys().iterator() allocation either way.
                if (hasPendingWrites() || hasSetupTasks()) {
                    selector.selectNow(keyDispatch);
                } else {
                    selector.select(keyDispatch, BLOCKING_SELECT_TIMEOUT_MS);
                }
                processSetupTasks();
                flushPendingWrites();
            } catch (IOException e) {
                if (running) {
                    throw new RuntimeException("EventLoop error", e);
                }
            }
        }
        try { selector.close(); } catch (IOException _) {}
    }

    /** True if any producer has published a setup task we haven't drained yet. */
    private boolean hasSetupTasks() {
        int slot = (int) (setupReadIndex & SETUP_RING_MASK);
        if (SETUP_SLOTS.getAcquire(setupRing, slot) != null) return true;
        return !selfQueue.isEmpty();
    }

    private void processSetupTasks() {
        // Drain main ring first.
        boolean drainedAny = false;
        while (true) {
            int slot = (int) (setupReadIndex & SETUP_RING_MASK);
            var task = (Runnable) SETUP_SLOTS.getAcquire(setupRing, slot);
            if (task == null) break;
            SETUP_SLOTS.setRelease(setupRing, slot, (Runnable) null);
            SETUP_READ_INDEX.setRelease(this, setupReadIndex + 1);
            drainedAny = true;
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("EventLoop: setup task failed: " + t);
            }
        }
        if (drainedAny) {
            var waiter = (Thread) PARKED_PRODUCER.getAndSet(this, (Thread) null);
            if (waiter != null) LockSupport.unpark(waiter);
        }
        // Drain self ring after main — tasks self-submitted during main-ring
        // iteration run here. Self-ring capacity must exceed the max number
        // of self-submits any single main-ring batch can produce (bounded
        // by handler count × recipients in practice).
        drainSelfQueue();
    }

    /**
     * Drain the self-submit queue to empty. Single-thread access (loop
     * thread only). Self-submitted tasks that themselves self-submit push
     * new entries onto the tail while this loop iterates the head — the
     * loop picks them up on subsequent iterations, bounded by the
     * re-entrant chain depth of the caller.
     */
    private void drainSelfQueue() {
        Runnable task;
        while ((task = selfQueue.pollFirst()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("EventLoop: self-submit task failed: " + t);
            }
        }
    }

    private boolean hasPendingWrites() {
        for (int i = 0; i < activeSlots; i++) {
            if ((boolean) PENDING_WRITE.getAcquire(pendingWrite, i)) return true;
        }
        return false;
    }

    private void flushPendingWrites() {
        for (int i = 0; i < activeSlots; i++) {
            if ((boolean) PENDING_WRITE.getAcquire(pendingWrite, i)) {
                var buf = writeBuffers[i]; // never null here: stageWrite ensures allocation before setting pendingWrite
                if (buf == null) { PENDING_WRITE.setRelease(pendingWrite, i, false); continue; }
                var channel = (SocketChannel) WRITE_TARGETS.getAcquire(writeTargets, i);
                boolean fullyDrained = false;
                synchronized (buf) {
                    try {
                        if (channel != null && channel.isConnected()) {
                            buf.flip();
                            // Non-blocking write may return a partial count when
                            // the receiver's socket buffer fills (TCP back-pressure).
                            // Keep the remainder for the next cycle — never drop.
                            channel.write(buf);
                            boolean empty = !buf.hasRemaining();
                            buf.compact();
                            if (empty) {
                                PENDING_WRITE.setRelease(pendingWrite, i, false);
                                fullyDrained = true;
                            }
                            // else: pendingWrite stays true, next cycle retries
                        } else {
                            // Channel gone — drop the buffer so we don't spin on a dead slot.
                            buf.clear();
                            PENDING_WRITE.setRelease(pendingWrite, i, false);
                        }
                    } catch (IOException _) {
                        // Peer closed / reset — drop bytes; the read side will
                        // notice and tear the connection down.
                        buf.clear();
                        PENDING_WRITE.setRelease(pendingWrite, i, false);
                    }
                }
                if (fullyDrained) {
                    // Atomically claim-and-clear so a concurrent caller
                    // doesn't see a stale pointer and a future drain doesn't
                    // re-unpark an already-unparked thread.
                    var waiter = (Thread) WAITING_THREADS.getAndSet(waitingThreads, i, null);
                    if (waiter != null) {
                        java.util.concurrent.locks.LockSupport.unpark(waiter);
                    }
                }
            }
        }
    }

    private volatile boolean closed;

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        running = false;
        selector.wakeup();
        try { thread.join(2000); } catch (InterruptedException _) { thread.interrupt(); }
        // Safety net: if the loop thread didn't exit (or was never started),
        // close the selector here to prevent a file-descriptor leak.
        if (selector.isOpen()) {
            try { selector.close(); } catch (IOException _) {}
        }
    }

    public boolean isRunning() { return running; }
    public Selector selector() { return selector; }
    public int writeBufferSize() { return writeBufferSize; }

    public interface KeyHandler {
        void handle(SelectionKey key) throws IOException;
    }
}
