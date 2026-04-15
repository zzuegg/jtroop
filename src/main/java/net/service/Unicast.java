package net.service;

import net.session.ConnectionId;

public interface Unicast {
    void send(ConnectionId target, Record message);
}
