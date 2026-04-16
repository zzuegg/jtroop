package jtroop.core;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class EventLoop implements Runnable, AutoCloseable {

    // VarHandles for release/acquire access to array slots so that writes
    // made by external threads are seen by the loop thread (and vice-versa)
    // without full-volatile reads on every iteration. Also prevents the JIT
    // from hoisting the stageWriteAndFlush spin-wait read out of its loop.
    private static final VarHandle PENDING_WRITE;
    private static final VarHandle WRITE_TARGETS;
    static {
        try {
            PENDING_WRITE = MethodHandles.arrayElementVarHandle(boolean[].class);
            WRITE_TARGETS = MethodHandles.arrayElementVarHandle(SocketChannel[].class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final Selector selector;
    private final Thread thread;
    private volatile boolean running;

    private final Queue<Runnable> setupQueue = new ConcurrentLinkedQueue<>();

    private final ByteBuffer[] writeBuffers;
    private final boolean[] pendingWrite;
    private final SocketChannel[] writeTargets;
    private final Thread[] waitingThreads;
    private final int maxConnections;
    private volatile int activeSlots = 0;

    public EventLoop(String name, int maxConnections) throws IOException {
        this.selector = Selector.open();
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
        this.maxConnections = maxConnections;
        this.writeBuffers = new ByteBuffer[maxConnections];
        this.pendingWrite = new boolean[maxConnections];
        this.writeTargets = new SocketChannel[maxConnections];
        this.waitingThreads = new Thread[maxConnections];
        for (int i = 0; i < maxConnections; i++) {
            writeBuffers[i] = ByteBuffer.allocateDirect(8192);
        }
    }

    public EventLoop(String name) throws IOException {
        this(name, 64);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public void submit(Runnable task) {
        setupQueue.add(task);
        selector.wakeup();
    }

    /**
     * Hot path: stage bytes for writing. Uses synchronized on the per-slot buffer
     * (biased locking ~6ns uncontended).
     *
     * Back-pressure: if the staging buffer cannot hold the new data, we drain
     * what the socket accepts (compact — no data loss), then wake the EventLoop
     * so it can continue draining. If room is still unavailable, spin-yield up
     * to 5s. No bytes are silently dropped.
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
        }
        // Slow path: buffer full — drain what fits, spin until room is available.
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (true) {
            boolean roomAvailable;
            synchronized (buf) {
                if (channel != null && channel.isConnected()) {
                    buf.flip();
                    try { channel.write(buf); } catch (IOException _) {}
                    buf.compact();
                    if (buf.position() > 0) {
                        PENDING_WRITE.setRelease(pendingWrite, slot, true);
                    }
                }
                roomAvailable = buf.remaining() >= need;
                if (roomAvailable) {
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
     * Stage bytes and block until the EventLoop has flushed them to the socket.
     */
    public void stageWriteAndFlush(int slot, ByteBuffer data) {
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

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(1);
                processSetupTasks();
                flushPendingWrites();
                processSelectedKeys();
            } catch (IOException e) {
                if (running) {
                    throw new RuntimeException("EventLoop error", e);
                }
            }
        }
        try { selector.close(); } catch (IOException _) {}
    }

    private void processSetupTasks() {
        Runnable task;
        while ((task = setupQueue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                // Setup tasks often register channels that may have been closed
                // by another thread (Client.close() during start()). One failing
                // task must not kill the loop.
                System.err.println("EventLoop: setup task failed: " + t);
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

    private void processSelectedKeys() throws IOException {
        var keys = selector.selectedKeys();
        var iter = keys.iterator();
        while (iter.hasNext()) {
            var key = iter.next();
            iter.remove();
            if (!key.isValid()) continue;
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
