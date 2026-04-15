package net.transport;

import java.net.InetSocketAddress;

public record UdpTransport(InetSocketAddress address) implements Transport {
    @Override public boolean isTcp() { return false; }
    @Override public boolean isUdp() { return true; }
}
