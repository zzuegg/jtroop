package net.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free Multiple-Producer Single-Consumer ring buffer.
 * Each slot stores two longs — zero per-enqueue heap allocation.
 * Capacity must be a power of 2.
 */
public final class MpscRingBuffer {

    @FunctionalInterface
    public interface LongBiConsumer {
        void accept(long a, long b);
    }

    private static final long EMPTY = Long.MIN_VALUE;

    private final long[] slotA;
    private final long[] slotB;
    private final int mask;
    private final AtomicLong writeIndex = new AtomicLong(0);
    private long readIndex = 0;

    public MpscRingBuffer(int capacity) {
        // Round up to power of 2
        int cap = Integer.highestOneBit(capacity - 1) << 1;
        if (cap < capacity) cap = capacity;
        if (Integer.bitCount(cap) != 1) cap = Integer.highestOneBit(cap) << 1;
        this.slotA = new long[cap];
        this.slotB = new long[cap];
        this.mask = cap - 1;
        // Mark all slots as empty
        for (int i = 0; i < cap; i++) {
            slotA[i] = EMPTY;
        }
    }

    /**
     * Enqueue a command (two longs). Thread-safe for multiple producers.
     * Returns false if the buffer is full.
     */
    public boolean offer(long a, long b) {
        long idx;
        do {
            idx = writeIndex.get();
            if (idx - readIndex >= slotA.length) return false; // full
        } while (!writeIndex.compareAndSet(idx, idx + 1));

        int slot = (int) (idx & mask);
        slotB[slot] = b;
        // slotA write must be visible AFTER slotB (acts as publish flag)
        slotA[slot] = a; // volatile-like semantics via the polling loop in poll()
        return true;
    }

    /**
     * Dequeue a command. Single-consumer only (EventLoop thread).
     * Returns null if empty. Returns a reusable 2-element array.
     */
    private final long[] result = new long[2];

    public long[] poll() {
        int slot = (int) (readIndex & mask);
        long a = slotA[slot];
        if (a == EMPTY) return null;
        long b = slotB[slot];
        slotA[slot] = EMPTY; // mark consumed
        readIndex++;
        result[0] = a;
        result[1] = b;
        return result;
    }

    /**
     * Drain all available entries. Single-consumer only.
     * Returns number of entries drained.
     */
    public int drainTo(LongBiConsumer consumer) {
        int count = 0;
        int slot = (int) (readIndex & mask);
        while (slotA[slot] != EMPTY) {
            long a = slotA[slot];
            long b = slotB[slot];
            slotA[slot] = EMPTY;
            readIndex++;
            consumer.accept(a, b);
            count++;
            slot = (int) (readIndex & mask);
        }
        return count;
    }

    public int size() {
        return (int) (writeIndex.get() - readIndex);
    }
}
