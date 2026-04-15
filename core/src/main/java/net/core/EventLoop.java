package net.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class EventLoop implements Runnable, AutoCloseable {

    private final Selector selector;
    private final Thread thread;
    private volatile boolean running;
    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

    public EventLoop(String name) throws IOException {
        this.selector = Selector.open();
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public void submit(Runnable task) {
        taskQueue.add(task);
        selector.wakeup();
    }

    public SelectionKey registerServerSocket(ServerSocketChannel channel, Object attachment) throws IOException {
        channel.configureBlocking(false);
        return channel.register(selector, SelectionKey.OP_ACCEPT, attachment);
    }

    public SelectionKey registerChannel(SelectableChannel channel, int ops, Object attachment) throws IOException {
        channel.configureBlocking(false);
        return channel.register(selector, ops, attachment);
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(100);
                processTasks();
                processSelectedKeys();
            } catch (IOException e) {
                if (running) {
                    throw new RuntimeException("EventLoop error", e);
                }
            }
        }
        try { selector.close(); } catch (IOException _) {}
    }

    private void processTasks() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            task.run();
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
