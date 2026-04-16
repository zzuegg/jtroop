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
    // without resorting to full-volatile reads on every iteration.
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

    // Hot path: per-slot write buffers with lightweight spin-lock
    // Writer copies bytes in, sets dirty flag. EventLoop drains on each cycle.
    private final ByteBuffer[] writeBuffers;
    private final boolean[] pendingWrite;
    private final SocketChannel[] writeTargets;
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
     * (biased locking makes uncontended case ~6ns). EventLoop flushes on each cycle.
     */
    public void stageWrite(int slot, ByteBuffer data) {
        var buf = writeBuffers[slot];
        var channel = (SocketChannel) WRITE_TARGETS.getAcquire(writeTargets, slot);
        synchronized (buf) {
            if (buf.remaining() < data.remaining() && channel != null) {
                buf.flip();
                try { channel.write(buf); } catch (IOException _) {}
                buf.clear();
            }
            buf.put(data);
            // Release ordering: ensures the buffer contents are visible to
            // the loop thread before it observes pendingWrite == true.
            PENDING_WRITE.setRelease(pendingWrite, slot, true);
        }
    }

    // Per-slot thread to unpark after flush (for blocking send)
    private final Thread[] waitingThreads;

    /**
     * Stage bytes and block until the EventLoop has flushed them to the socket.
     * Goes through the full EventLoop path: stage → wake selector → EventLoop flushes → unpark caller.
     * Zero allocation — uses LockSupport.park/unpark.
     */
    public void stageWriteAndFlush(int slot, ByteBuffer data) {
        stageWrite(slot, data);
        selector.wakeup();
        // Acquire-read so we observe the flush that the loop thread performed
        // with setRelease. A plain array read could be hoisted by the JIT and
        // spin forever.
        while ((boolean) PENDING_WRITE.getAcquire(pendingWrite, slot)) {
            Thread.onSpinWait();
        }
    }

    public void flush() {
        selector.wakeup();
    }

    public void registerWriteTarget(int slot, SocketChannel channel) {
        // Release-store: ensures other threads that acquire-read this slot
        // also see the channel's constructor-published state.
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
                // Setup tasks often register channels that may have been
                // closed by another thread (Client.close() during start()).
                // A single failing task must not kill the loop.
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
                // Guard the write: the channel may race-close between the
                // isConnected() check and the write() call (e.g. server
                // force-disconnect, peer RST). A ClosedChannelException must
                // NOT take down the loop — it serves many connections.
                synchronized (buf) {
                    try {
                        if (channel != null && channel.isConnected()) {
                            buf.flip();
                            channel.write(buf);
                            buf.compact();
                        } else {
                            // Channel gone — drop pending bytes so we don't
                            // spin forever on a dead slot.
                            buf.clear();
                        }
                    } catch (IOException _) {
                        // Peer closed / reset under us — drop the buffer and
                        // clear the pending flag; the read side will notice
                        // and tear the connection down properly.
                        buf.clear();
                    }
                    // Release-store so stageWriteAndFlush's acquire-read
                    // observes the flushed/abandoned state with proper happens-before.
                    PENDING_WRITE.setRelease(pendingWrite, i, false);
                }
                // Unpark any thread waiting for this slot's flush
                var waiter = waitingThreads[i];
                if (waiter != null) {
                    java.util.concurrent.locks.LockSupport.unpark(waiter);
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
                    // A single misbehaving connection must not take down the
                    // EventLoop (which serves many connections). Cancel this
                    // key, close the channel, and continue — other connections
                    // keep running.
                    try { key.cancel(); } catch (Throwable _) {}
                    try { key.channel().close(); } catch (Throwable _) {}
                    // Surface for diagnostics but don't rethrow.
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
