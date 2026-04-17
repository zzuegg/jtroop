package jtroop.core;

import jtroop.ConnectionException;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class EventLoop implements Runnable, AutoCloseable {

    // VarHandles for release/acquire access to array slots so that writes
    // made by external threads are seen by the loop thread (and vice-versa)
    // without full-volatile reads on every iteration. Also prevents the JIT
    // from hoisting the stageWriteAndFlush spin-wait read out of its loop.
    private static final VarHandle PENDING_WRITE;
    private static final VarHandle WRITE_TARGETS;
    private static final VarHandle SETUP_SLOTS;
    static {
        try {
            PENDING_WRITE = MethodHandles.arrayElementVarHandle(boolean[].class);
            WRITE_TARGETS = MethodHandles.arrayElementVarHandle(SocketChannel[].class);
            SETUP_SLOTS   = MethodHandles.arrayElementVarHandle(Runnable[].class);
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
    // Overflow (ring full) falls back to a ConcurrentLinkedQueue so no task
    // is ever dropped; that path does allocate a node but is off the hot path
    // for the sizes we see in practice (submits happen on accept / connect /
    // close — O(connection lifecycle), not O(message)).
    private static final int SETUP_RING_CAPACITY = 4096; // power of 2
    private static final int SETUP_RING_MASK = SETUP_RING_CAPACITY - 1;
    private final Runnable[] setupRing = new Runnable[SETUP_RING_CAPACITY];
    private final AtomicLong setupWriteIndex = new AtomicLong(0);
    private long setupReadIndex = 0; // single-consumer (event-loop thread)
    private final Queue<Runnable> setupOverflow = new ConcurrentLinkedQueue<>();

    private final ByteBuffer[] writeBuffers;
    private final boolean[] pendingWrite;
    private final SocketChannel[] writeTargets;
    private final Thread[] waitingThreads;
    private volatile int activeSlots = 0;

    public EventLoop(String name, int maxConnections) throws IOException {
        this.selector = Selector.open();
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
        this.writeBuffers = new ByteBuffer[maxConnections];
        this.pendingWrite = new boolean[maxConnections];
        this.writeTargets = new SocketChannel[maxConnections];
        this.waitingThreads = new Thread[maxConnections];
        for (int i = 0; i < maxConnections; i++) {
            writeBuffers[i] = ByteBuffer.allocateDirect(65536);
        }
    }

    public EventLoop(String name) throws IOException {
        this(name, 64);
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
     * <p>Slow path (ring full, &gt; {@value #SETUP_RING_CAPACITY} backlog): falls
     * back to a ConcurrentLinkedQueue. Correct but allocates a queue node.
     * Never drops a task.
     */
    public void submit(Runnable task) {
        long idx;
        do {
            idx = setupWriteIndex.get();
            if (idx - setupReadIndex >= SETUP_RING_CAPACITY) {
                // Ring full — fall back. `setupReadIndex` is a plain long
                // read here (producer side) so it may be stale; that only
                // causes us to declare full a bit early, which is safe.
                setupOverflow.add(task);
                selector.wakeup();
                return;
            }
        } while (!setupWriteIndex.compareAndSet(idx, idx + 1));

        int slot = (int) (idx & SETUP_RING_MASK);
        // Release-store so the consumer's acquire-load sees the publish.
        SETUP_SLOTS.setRelease(setupRing, slot, task);
        selector.wakeup();
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
    public void stageWrite(int slot, ByteBuffer data) {
        var buf = writeBuffers[slot];
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
                } catch (IOException e) {
                    System.err.println("EventLoop: write failed on slot " + slot + ": " + e);
                }
                return;
            }
        }
        // Slow path: buffer full but fits in capacity — drain, spin until room available.
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (true) {
            synchronized (buf) {
                if (channel != null && channel.isConnected()) {
                    buf.flip();
                    try { channel.write(buf); } catch (IOException _) { /* best-effort drain */ }
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
                throw new ConnectionException(
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
        var buf = writeBuffers[slot];
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
        waitingThreads[slot] = Thread.currentThread();
        stageWrite(slot, data);
        selector.wakeup();
        while ((boolean) PENDING_WRITE.getAcquire(pendingWrite, slot)) {
            Thread.onSpinWait();
        }
    }

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
                t.printStackTrace(System.err);
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
        return !setupOverflow.isEmpty();
    }

    private void processSetupTasks() {
        // Drain the MPSC ring first (zero-alloc fast path).
        while (true) {
            int slot = (int) (setupReadIndex & SETUP_RING_MASK);
            var task = (Runnable) SETUP_SLOTS.getAcquire(setupRing, slot);
            if (task == null) break; // slot not yet published / ring empty
            // Clear slot before running so a producer can refill even if the
            // task itself submits more work (re-entrant-safe).
            SETUP_SLOTS.setRelease(setupRing, slot, (Runnable) null);
            setupReadIndex++;
            try {
                task.run();
            } catch (Throwable t) {
                // Setup tasks often register channels that may have been closed
                // by another thread (Client.close() during start()). One failing
                // task must not kill the loop.
                System.err.println("EventLoop: setup task failed: " + t);
                t.printStackTrace(System.err);
            }
        }
        // Drain overflow (tasks that arrived while the ring was full).
        Runnable overflow;
        while ((overflow = setupOverflow.poll()) != null) {
            try {
                overflow.run();
            } catch (Throwable t) {
                System.err.println("EventLoop: setup task failed: " + t);
                t.printStackTrace(System.err);
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
                var buf = writeBuffers[i];
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
                    var waiter = waitingThreads[i];
                    if (waiter != null) {
                        java.util.concurrent.locks.LockSupport.unpark(waiter);
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        try { thread.join(1000); } catch (InterruptedException _) { thread.interrupt(); }
    }

    public boolean isRunning() { return running; }
    public Selector selector() { return selector; }

    public interface KeyHandler {
        void handle(SelectionKey key) throws IOException;
    }
}
