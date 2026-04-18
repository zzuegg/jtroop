package jtroop.pipeline.layers;

import jtroop.ConnectionException;
import jtroop.client.Client;
import jtroop.pipeline.Layer;
import jtroop.pipeline.LayerContext;
import jtroop.pipeline.Pipeline;
import jtroop.server.Server;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AllowListLayerTest {

    record GameConnection(int version) {}
    record Ping(int seq) {}
    record Pong(int seq) {}

    interface PingService { Pong ping(Ping p); }

    @Handles(PingService.class)
    static class PingHandler {
        final CopyOnWriteArrayList<Ping> received = new CopyOnWriteArrayList<>();
        @OnMessage
        Pong ping(Ping p, ConnectionId sender) {
            received.add(p);
            return new Pong(p.seq());
        }
    }

    /** Unit-level: allow-listed peer passes through, decisions are cached. */
    @Test
    void allowedAddress_passesThrough_andCachesDecision() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var layer = new AllowListLayer(Set.of(loopback));

        boolean[] closedFlag = {false};
        var ctx = new LayerContext(
                0x00000001_00000042L,
                new InetSocketAddress(loopback, 55555),
                System.nanoTime(),
                () -> closedFlag[0] = true,
                () -> closedFlag[0] = true);

        var wire = ByteBuffer.allocate(16);
        wire.putInt(0xCAFE);
        wire.flip();
        // First call: checks allow-list, caches true.
        var out = layer.decodeInbound(ctx, wire);
        assertSame(wire, out, "allow-listed peer should pass through unchanged");
        assertEquals(1, layer.cachedCount());
        assertFalse(closedFlag[0], "allow-listed peer must not close");

        // Second call: hits cache, still passes through.
        wire.rewind();
        out = layer.decodeInbound(ctx, wire);
        assertSame(wire, out);
        assertEquals(1, layer.cachedCount(), "decision must be cached, not re-checked");
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) is semantically the same host as
     *  a.b.c.d — the allow-list must accept either form. Pre-fix, an
     *  allow-list containing {@code 127.0.0.1} denied a peer whose
     *  connecting socket reported {@code ::ffff:127.0.0.1} (common on
     *  dual-stack JVMs), breaking legitimate loopback connectivity and
     *  conversely allowing an attacker on a dual-stack host to evade a
     *  deny-list entry by connecting via the other family. */
    @Test
    void ipv4MappedIpv6_treatedAsSameHost() throws Exception {
        var loopback4 = InetAddress.getByName("127.0.0.1");
        // Force construction as an actual 16-byte Inet6Address — this is what
        // a dual-stack Java NIO socket reports as the remote address when an
        // IPv4 peer connects via an IPv6 listener. InetAddress.getByName for
        // "::ffff:127.0.0.1" auto-normalises to Inet4Address in the common JDK,
        // so we build the IPv6 mapped form byte-wise.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 127;
        mapped[13] = 0;
        mapped[14] = 0;
        mapped[15] = 1;
        var mapped6 = java.net.Inet6Address.getByAddress(null, mapped, 0);

        // Layer is configured with the IPv4 form only.
        var layer = new AllowListLayer(Set.of(loopback4));

        boolean[] closed6 = {false};
        var ctx6 = new LayerContext(
                0x00000001_00000001L,
                new InetSocketAddress(mapped6, 55555),
                System.nanoTime(),
                () -> closed6[0] = true,
                () -> closed6[0] = true);

        var wire = ByteBuffer.allocate(8);
        wire.putInt(1);
        wire.flip();

        // Same host via IPv4-mapped IPv6 must pass through.
        var out = layer.decodeInbound(ctx6, wire);
        assertSame(wire, out, "IPv4-mapped IPv6 form of an allow-listed IPv4 must pass");
        assertFalse(closed6[0], "IPv4-mapped IPv6 of allow-listed peer must not close");

        // And the reverse direction — layer configured with IPv6 form,
        // peer arrives as IPv4 — also matches.
        var layer6 = new AllowListLayer(Set.of(mapped6));
        boolean[] closed4 = {false};
        var ctx4 = new LayerContext(
                0x00000001_00000002L,
                new InetSocketAddress(loopback4, 44444),
                System.nanoTime(),
                () -> closed4[0] = true,
                () -> closed4[0] = true);
        wire.rewind();
        var out2 = layer6.decodeInbound(ctx4, wire);
        assertSame(wire, out2,
                "IPv4 form of an IPv4-mapped IPv6 allow-listed peer must pass");
        assertFalse(closed4[0]);
    }

    /** Unit-level: denied peer triggers closeNow() and decode returns null. */
    @Test
    void deniedAddress_callsCloseNow_andReturnsNull() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var layer = new AllowListLayer(Set.of(loopback));

        boolean[] closed = {false};
        var deniedCtx = new LayerContext(
                0x00000001_00000099L,
                new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 9999),
                System.nanoTime(),
                () -> closed[0] = true,
                () -> closed[0] = true);

        var wire = ByteBuffer.allocate(16);
        wire.putInt(0xDEAD);
        wire.flip();

        var out = layer.decodeInbound(deniedCtx, wire);
        assertNull(out, "denied peer must receive no frame");
        assertTrue(closed[0], "denied peer must trigger close");
    }

    /** Integration: a server with an allow-list including 127.0.0.1 accepts
     *  a loopback client; flipping the allow-list to a different address
     *  slams the door on a fresh connection. */
    @Test
    void loopbackAllowed_roundtripSucceeds() throws Exception {
        var handler = new PingHandler();
        var loopback = InetAddress.getByName("127.0.0.1");
        var serverLayer = new AllowListLayer(Set.of(loopback));

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0),
                        serverLayer, new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port),
                        new FramingLayer())
                .addService(PingService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        var pong = client.request(new Ping(1), Pong.class);
        // Key assertion: allow-list permitted the connection, ping round-tripped.
        assertEquals(1, pong.seq(), "loopback allow-listed peer should round-trip");

        client.close();
        server.close();
    }

    /** Unit-level: Layer.onConnectionClose evicts the cached decision. */
    @Test
    void onConnectionClose_evictsCachedDecision() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var layer = new AllowListLayer(Set.of(loopback));

        long connId = 0x00000001_00000042L;
        var ctx = new LayerContext(
                connId,
                new InetSocketAddress(loopback, 55555),
                System.nanoTime(),
                () -> {}, () -> {});
        var wire = ByteBuffer.allocate(8);
        wire.putInt(1);
        wire.flip();
        layer.decodeInbound(ctx, wire);
        assertEquals(1, layer.cachedCount(), "decision should be cached after first decode");

        layer.onConnectionClose(connId);
        assertEquals(0, layer.cachedCount(), "decision must be evicted on close");
    }

    /** Integration: rapid connect/disconnect cycles do NOT leak cached
     *  decisions — Pipeline.onConnectionClose fires on every close path. */
    @Test
    void rapidConnectDisconnect_doesNotLeakCachedDecisions() throws Exception {
        var handler = new PingHandler();
        var loopback = InetAddress.getByName("127.0.0.1");
        var serverLayer = new AllowListLayer(Set.of(loopback));

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0),
                        serverLayer, new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        final int cycles = 20;
        for (int i = 0; i < cycles; i++) {
            var client = Client.builder()
                    .connect(GameConnection.class, Transport.tcp("localhost", port),
                            new FramingLayer())
                    .addService(PingService.class, GameConnection.class)
                    .build();
            client.start();
            var pong = client.request(new Ping(i), Pong.class);
            assertEquals(i, pong.seq());
            client.close();
        }
        // Wait briefly for the close dispatch on the server side.
        long deadline = System.currentTimeMillis() + 2_000;
        while (serverLayer.cachedCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(0, serverLayer.cachedCount(),
                "decisions must be evicted on every close; leaked "
                        + serverLayer.cachedCount() + " after " + cycles + " cycles");
        server.close();
    }

    @Test
    void loopbackDenied_clientRequestFails() throws Exception {
        var handler = new PingHandler();
        var denylist = new AllowListLayer(
                Set.of(InetAddress.getByName("10.254.254.254"))); // never loopback

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0),
                        denylist, new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port),
                        new FramingLayer())
                .addService(PingService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        // Key assertion: denied peer gets no response; request times out.
        assertThrows(RuntimeException.class,
                () -> client.request(new Ping(1), Pong.class),
                "denied peer request must not succeed");

        client.close();
        server.close();
    }
}
