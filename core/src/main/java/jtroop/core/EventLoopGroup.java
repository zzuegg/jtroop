package jtroop.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class EventLoopGroup implements AutoCloseable {

    private final EventLoop[] loops;
    private final AtomicInteger index = new AtomicInteger(0);

    public EventLoopGroup(int size, int writeBufferSize) throws IOException {
        loops = new EventLoop[size];
        for (int i = 0; i < size; i++) {
            loops[i] = new EventLoop("event-loop-" + i, 64, writeBufferSize);
        }
    }

    public EventLoopGroup(int size) throws IOException {
        this(size, 65536);
    }

    public EventLoopGroup() throws IOException {
        this(Runtime.getRuntime().availableProcessors());
    }

    public void start() {
        for (var loop : loops) {
            loop.start();
        }
    }

    public EventLoop next() {
        return loops[Math.floorMod(index.getAndIncrement(), loops.length)];
    }

    public EventLoop get(int i) {
        return loops[i];
    }

    public int size() {
        return loops.length;
    }

    @Override
    public void close() {
        for (var loop : loops) {
            loop.close();
        }
    }
}
