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
        assertThrows(ConnectionException.class,
                () -> client.request(new Ping(1), Pong.class),
                "denied peer request must not succeed");

        client.close();
        server.close();
    }
}
