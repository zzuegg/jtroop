package net.session;

import java.util.function.Consumer;

public final class SessionStore {

    private final int capacity;
    private final int[] generations;
    private final boolean[] active;
    private final int[] states;
    private final long[] lastActivity;
    private int count;
    private int freeHead;

    // Free list stored in states array when slot is inactive
    private static final int END_OF_FREE = -1;

    public SessionStore(int capacity) {
        this.capacity = capacity;
        this.generations = new int[capacity];
        this.active = new boolean[capacity];
        this.states = new int[capacity];
        this.lastActivity = new long[capacity];
        this.count = 0;
        // Build free list
        for (int i = 0; i < capacity - 1; i++) {
            states[i] = i + 1;
        }
        states[capacity - 1] = END_OF_FREE;
        freeHead = 0;
    }

    public ConnectionId allocate() {
        if (freeHead == END_OF_FREE) {
            throw new IllegalStateException("SessionStore full (capacity=" + capacity + ")");
        }
        int index = freeHead;
        freeHead = states[index]; // next free
        generations[index]++;
        active[index] = true;
        states[index] = 0;
        lastActivity[index] = 0;
        count++;
        return ConnectionId.of(index, generations[index]);
    }

    public void release(ConnectionId id) {
        int index = id.index();
        if (!active[index] || generations[index] != id.generation()) return;
        active[index] = false;
        states[index] = freeHead;
        freeHead = index;
        count--;
    }

    public boolean isActive(ConnectionId id) {
        int index = id.index();
        return index >= 0 && index < capacity
                && active[index]
                && generations[index] == id.generation();
    }

    public void setState(ConnectionId id, int state) {
        states[id.index()] = state;
    }

    public int getState(ConnectionId id) {
        return states[id.index()];
    }

    public void setLastActivity(ConnectionId id, long nanos) {
        lastActivity[id.index()] = nanos;
    }

    public long getLastActivity(ConnectionId id) {
        return lastActivity[id.index()];
    }

    public int activeCount() {
        return count;
    }

    public void forEachActive(Consumer<ConnectionId> consumer) {
        for (int i = 0; i < capacity; i++) {
            if (active[i]) {
                consumer.accept(ConnectionId.of(i, generations[i]));
            }
        }
    }
}
