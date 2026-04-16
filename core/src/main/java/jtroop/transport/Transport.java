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
        return new UdpTransport(new InetSocketAddress(port), false);
    }

    static UdpTransport udp(String host, int port) {
        return new UdpTransport(new InetSocketAddress(host, port), false);
    }

    /**
     * UDP listen/connect descriptor pinned to a single peer ("connected UDP").
     * The server accepts packets from the FIRST peer only; the client sends to
     * the target address only. Zero-allocation on the receive hot path — see
     * {@link UdpTransport}.
     *
     * <p>Use {@link #udp(int)} / {@link #udp(String, int)} for multi-peer UDP.
     */
    static UdpTransport udpConnected(int port) {
        return new UdpTransport(new InetSocketAddress(port), true);
    }

    static UdpTransport udpConnected(String host, int port) {
        return new UdpTransport(new InetSocketAddress(host, port), true);
    }

    InetSocketAddress address();
    boolean isTcp();
    boolean isUdp();
}
