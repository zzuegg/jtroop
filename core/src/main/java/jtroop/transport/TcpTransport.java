package jtroop.transport;

import java.net.InetSocketAddress;

public record TcpTransport(InetSocketAddress address) implements Transport {
    @Override public boolean isTcp() { return true; }
    @Override public boolean isUdp() { return false; }
}
