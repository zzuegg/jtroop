package jtroop.service;

import jtroop.session.ConnectionId;

public interface Unicast {
    void send(ConnectionId target, Record message);
}
