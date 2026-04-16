package jtroop.transport;

import java.net.InetSocketAddress;

/**
 * UDP listen/connect descriptor.
 *
 * <p>When {@link #connected()} is {@code true}, the channel is promoted to a
 * connected {@link java.nio.channels.DatagramChannel} as soon as the first peer
 * is known (client: immediately, server: on first received packet). Connected
 * channels allow the hot path to call {@code channel.read(buf)} instead of
 * {@code channel.receive(buf)}, which avoids the per-packet
 * {@link InetSocketAddress} allocation (~32 B/op) that {@code receive()} makes.
 *
 * <p>Trade-off: a connected {@link java.nio.channels.DatagramChannel} ignores
 * datagrams from any peer other than the one it is connected to. Use the
 * {@link Transport#udp(int)} variant (unconnected) when the listener must
 * accept datagrams from multiple peers.
 */
public record UdpTransport(InetSocketAddress address, boolean connected) implements Transport {
    public UdpTransport(InetSocketAddress address) { this(address, false); }
    @Override public boolean isTcp() { return false; }
    @Override public boolean isUdp() { return true; }
}
