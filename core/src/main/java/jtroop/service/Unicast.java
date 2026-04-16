package jtroop.service;

import jtroop.session.ConnectionId;

public interface Unicast {
    void send(ConnectionId target, Record message);

    /**
     * No-op singleton used when no unicast transport is wired — e.g. during
     * unit tests or when a handler declares a {@link Unicast} parameter but
     * the registry is not attached to a server.
     */
    Unicast NO_OP = (target, message) -> {};
}
