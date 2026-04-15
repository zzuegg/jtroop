package jtroop.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class EventLoop implements Runnable, AutoCloseable {

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
            writeBuffers[i] = ByteBuffer.allocate(8192);
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
        var channel = writeTargets[slot];
        synchronized (buf) {
            if (buf.remaining() < data.remaining() && channel != null) {
                buf.flip();
                try { channel.write(buf); } catch (IOException _) {}
                buf.clear();
            }
            buf.put(data);
            pendingWrite[slot] = true;
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
        waitingThreads[slot] = Thread.currentThread();
        stageWrite(slot, data);
        selector.wakeup();
        // Park until EventLoop flushes this slot
        while (pendingWrite[slot]) {
            java.util.concurrent.locks.LockSupport.park();
        }
        waitingThreads[slot] = null;
    }

    public void flush() {
        selector.wakeup();
    }

    public void registerWriteTarget(int slot, SocketChannel channel) {
        writeTargets[slot] = channel;
        if (slot >= activeSlots) activeSlots = slot + 1;
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(1); // 1ms for low latency
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
            task.run();
        }
    }

    private void flushPendingWrites() throws IOException {
        for (int i = 0; i < activeSlots; i++) {
            if (pendingWrite[i]) {
                var buf = writeBuffers[i];
                var channel = writeTargets[i];
                if (channel != null && channel.isConnected()) {
                    synchronized (buf) {
                        buf.flip();
                        channel.write(buf);
                        buf.compact();
                        pendingWrite[i] = false;
                    }
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
                handler.handle(key);
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
