package jtroop.service;

public interface Broadcast {
    void send(Record message);

    /**
     * No-op singleton used when no broadcast transport is wired — e.g. during
     * unit tests or when a handler declares a {@link Broadcast} parameter but
     * the registry is not attached to a server.
     *
     * <p>Declared as a {@code static final} field so that a single shared
     * instance replaces per-call lambda allocations at non-server wire points
     * (see {@code Server#setBroadcast} for the live fan-out implementation).
     */
    Broadcast NO_OP = _ -> {};
}
