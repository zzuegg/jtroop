package jtroop.transport;

import java.net.InetSocketAddress;

public sealed interface Transport permits TcpTransport, UdpTransport {

    static TcpTransport tcp(int port) {
        return new TcpTransport(new InetSocketAddress(port));
    }

    static TcpTransport tcp(String host, int port) {
        return new TcpTransport(new InetSocketAddress(host, port));
    }

    static UdpTransport udp(int port) {
        return new UdpTransport(new InetSocketAddress(port));
    }

    static UdpTransport udp(String host, int port) {
        return new UdpTransport(new InetSocketAddress(host, port));
    }

    InetSocketAddress address();
    boolean isTcp();
    boolean isUdp();
}
