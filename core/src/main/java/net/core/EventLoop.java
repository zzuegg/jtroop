package net.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class EventLoop implements Runnable, AutoCloseable {

    private final Selector selector;
    private final Thread thread;
    private volatile boolean running;

    // Cold path: channel registration, setup tasks (rare, allocation OK)
    private final Queue<Runnable> setupQueue = new ConcurrentLinkedQueue<>();

    // Hot path: write commands (connection slot → pending write buffer)
    private final ByteBuffer[] writeBuffers;
    private final boolean[] pendingWrite;
    private final SocketChannel[] writeTargets;
    private final int maxConnections;
    private volatile int activeSlots = 0; // highest registered slot + 1

    public EventLoop(String name, int maxConnections) throws IOException {
        this.selector = Selector.open();
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
        this.maxConnections = maxConnections;
        this.writeBuffers = new ByteBuffer[maxConnections];
        this.pendingWrite = new boolean[maxConnections];
        this.writeTargets = new SocketChannel[maxConnections];
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

    /**
     * Cold path: submit a setup task (channel registration, etc.).
     * Allocates — only use for rare operations.
     */
    public void submit(Runnable task) {
        setupQueue.add(task);
        selector.wakeup();
    }

    /**
     * Hot path: stage bytes for writing to a connection slot.
     * Zero allocation — copies bytes into pre-allocated slot buffer,
     * sets pending flag, wakes selector.
     */
    public void stageWrite(int slot, ByteBuffer data) {
        var buf = writeBuffers[slot];
        var channel = writeTargets[slot];
        synchronized (buf) {
            // If buffer would overflow, flush first
            if (buf.remaining() < data.remaining() && channel != null) {
                buf.flip();
                try { channel.write(buf); } catch (java.io.IOException _) {}
                buf.clear();
            }
            buf.put(data);
            pendingWrite[slot] = true;
        }
        selector.wakeup();
    }

    /**
     * Register a connection slot for writing.
     */
    public void registerWriteTarget(int slot, SocketChannel channel) {
        writeTargets[slot] = channel;
        if (slot >= activeSlots) activeSlots = slot + 1;
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(100);
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
