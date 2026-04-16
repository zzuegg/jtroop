package jtroop.pipeline;

import jtroop.client.Client;
import jtroop.pipeline.layers.AckLayer;
import jtroop.pipeline.layers.AllowListLayer;
import jtroop.pipeline.layers.DuplicateFilterLayer;
import jtroop.pipeline.layers.SequencingLayer;
import jtroop.server.Server;
import jtroop.service.Datagram;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline tests for connected UDP (Transport.udpConnected).
 * Verifies:
 *  <ul>
 *    <li>Every packet goes through the pipeline with a proper Layer.Context.</li>
 *    <li>Filter layers (AllowListLayer) drop packets from non-allow-listed peers.</li>
 *    <li>Reliability layers (Sequencing + DuplicateFilter + Ack) wire up from
 *        the Server/Client builder and deliver packets end-to-end.</li>
 *    <li>Unconnected UDP + layers fails-fast at build time (Plan A).</li>
 *    <li>Unconnected UDP without layers keeps working.</li>
 *  </ul>
 */
@Timeout(20)
class UdpPipelineTest {

    record GameConn(int v) {}
    public record Ping(int seq) {}

    public interface GameService {
        @Datagram void ping(Ping p);
    }

    @Handles(GameService.class)
    public static class GameHandler {
        final CopyOnWriteArrayList<Ping> received = new CopyOnWriteArrayList<>();
        final AtomicInteger count = new AtomicInteger();
        CountDownLatch latch;

        @OnMessage
        void ping(Ping p, ConnectionId sender) {
            count.incrementAndGet();
            received.add(p);
            if (latch != null) latch.countDown();
        }
    }

    /**
     * Records decode/encode calls per-layer-invocation along with the observed
     * Context snapshot at that point. Public so the generated fused pipeline
     * can reference it via the hidden-class invokevirtual site.
     */
    public static final class CountingLayer implements Layer {
        public final CopyOnWriteArrayList<Long> decodeCtxIds = new CopyOnWriteArrayList<>();
        public final CopyOnWriteArrayList<Long> encodeCtxIds = new CopyOnWriteArrayList<>();
        public final CopyOnWriteArrayList<java.net.InetSocketAddress> decodePeers = new CopyOnWriteArrayList<>();

        @Override
        public void encodeOutbound(Context ctx, ByteBuffer payload, ByteBuffer out) {
            encodeCtxIds.add(ctx.connectionId());
            out.put(payload);
        }

        @Override
        public ByteBuffer decodeInbound(Context ctx, ByteBuffer wire) {
            decodeCtxIds.add(ctx.connectionId());
            decodePeers.add(ctx.remoteAddress());
            return wire;
        }
    }

    @Test
    void connectedUdp_pipelineRunsForEveryPacket() throws Exception {
        var handler = new GameHandler();
        var serverCounter = new CountingLayer();
        var clientCounter = new CountingLayer();

        var server = Server.builder()
                .listen(GameConn.class, Transport.udpConnected(0), serverCounter)
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.udpPort(GameConn.class);

        handler.latch = new CountDownLatch(5);

        var client = Client.builder()
                .connect(GameConn.class, Transport.udpConnected("localhost", port), clientCounter)
                .addService(GameService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(200);

        for (int i = 0; i < 5; i++) {
            client.send(new Ping(i));
            // Small throttle — UDP on loopback will drop packets sent tighter
            // than the kernel recv buffer drain rate. The benchmark already
            // shows this under sustained load.
            Thread.sleep(20);
        }
        assertTrue(handler.latch.await(5, TimeUnit.SECONDS),
                "server should receive all 5 packets; got " + handler.count.get());

        // Give pipeline's read threads a moment to record late arrivals.
        Thread.sleep(200);

        assertEquals(5, clientCounter.encodeCtxIds.size(),
                "client pipeline should encode every outbound packet");
        assertEquals(5, serverCounter.decodeCtxIds.size(),
                "server pipeline should decode every inbound packet");
        // Every encode must see the same stable connection id — a real Context,
        // not LayerContext.NOOP (which returns 0).
        long clientId = clientCounter.encodeCtxIds.get(0);
        assertNotEquals(0L, clientId, "client encode must see a real Context id");
        for (var id : clientCounter.encodeCtxIds) assertEquals(clientId, id);

        long serverId = serverCounter.decodeCtxIds.get(0);
        assertNotEquals(0L, serverId, "server decode must see a real Context id");
        for (var id : serverCounter.decodeCtxIds) assertEquals(serverId, id);

        // Peer address must be populated (not null) on the server decode side.
        assertNotNull(serverCounter.decodePeers.get(0),
                "server decode must see the peer's InetSocketAddress");

        client.close();
        server.close();
    }

    @Test
    void connectedUdp_filterLayerDropsUnauthorizedPeer() throws Exception {
        var handler = new GameHandler();
        // Allow-list an address that is NEVER loopback, so packets from 127.0.0.1
        // get rejected by the filter layer.
        var deniedList = new AllowListLayer(Set.of(InetAddress.getByName("10.254.254.254")));

        var server = Server.builder()
                .listen(GameConn.class, Transport.udpConnected(0), deniedList)
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.udpPort(GameConn.class);

        var client = Client.builder()
                .connect(GameConn.class, Transport.udpConnected("localhost", port))
                .addService(GameService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(200);

        handler.latch = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            client.send(new Ping(i));
        }
        // Packets should be dropped — latch never reaches 0.
        boolean received = handler.latch.await(1, TimeUnit.SECONDS);
        assertFalse(received, "packets from non-allow-listed peer must be dropped by the pipeline");
        assertEquals(0, handler.count.get(),
                "server handler must see ZERO packets from denied peer");

        client.close();
        server.close();
    }

    @Test
    void connectedUdp_reliabilityLayersViaBuilder() throws Exception {
        var handler = new GameHandler();
        // One Pipeline per listener; for connected UDP each Server listener
        // handles ONE peer so the layer instances are per-connection.
        // Layer order matters: encode runs left-to-right, decode reverses.
        //   Encode: payload → SequencingLayer (prepend seqSeq)
        //                   → AckLayer (prepend ackSeq)
        //                   → DuplicateFilterLayer (pass-through)
        //           wire = [ackSeq][seqSeq][payload]
        //   Decode: wire → DuplicateFilterLayer (peek ackSeq, check ring)
        //                → AckLayer (consume ackSeq)
        //                → SequencingLayer (consume seqSeq)
        // DupFilter MUST see the unique per-packet ackSeq, not the payload
        // header — otherwise identical-payload packets get flagged as dupes.
        var server = Server.builder()
                .listen(GameConn.class, Transport.udpConnected(0),
                        new SequencingLayer(),
                        new AckLayer(500),
                        new DuplicateFilterLayer(1024))
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.udpPort(GameConn.class);

        int count = 50;
        handler.latch = new CountDownLatch(count);

        var client = Client.builder()
                .connect(GameConn.class, Transport.udpConnected("localhost", port),
                        new SequencingLayer(),
                        new AckLayer(500),
                        new DuplicateFilterLayer(1024))
                .addService(GameService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(200);

        for (int i = 0; i < count; i++) {
            client.send(new Ping(i));
            // Pace sends. AckLayer retransmit is not driven by the pipeline
            // — a bidirectional protocol or a periodic driver would supply
            // retransmits on the next inbound packet, but this test is
            // unidirectional. Pacing keeps loopback loss at zero so we can
            // verify the layer stack plumbing without worrying about retries.
            Thread.sleep(5);
        }
        assertTrue(handler.latch.await(10, TimeUnit.SECONDS),
                "all " + count + " reliable packets should arrive; got " + handler.count.get());
        // Reliability: no duplicates reached the handler.
        assertEquals(count, handler.received.size(), "no duplicates should reach the handler");

        client.close();
        server.close();
    }

    @Test
    void unconnectedUdpWithLayers_buildFails() {
        // Plan A: unconnected UDP cannot carry pipeline layers — state would
        // be shared across all peers. Builder must reject loudly.
        var ex = assertThrows(IllegalArgumentException.class, () -> Server.builder()
                .listen(GameConn.class, Transport.udp(0), new SequencingLayer())
                .build());
        assertTrue(ex.getMessage().toLowerCase().contains("udpconnected"),
                "error should point users at Transport.udpConnected; was: " + ex.getMessage());

        var ex2 = assertThrows(IllegalArgumentException.class, () -> Client.builder()
                .connect(GameConn.class, Transport.udp("localhost", 1), new SequencingLayer())
                .build());
        assertTrue(ex2.getMessage().toLowerCase().contains("udpconnected"),
                "client error should also point at udpConnected; was: " + ex2.getMessage());
    }

    @Test
    void unconnectedUdpWithoutLayers_stillWorks() throws Exception {
        // Sanity: existing Transport.udp(port) use (no layers) must keep
        // working unchanged — this is the public API the rest of the suite
        // relies on.
        var handler = new GameHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.udp(0))
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.udpPort(GameConn.class);

        var client = Client.builder()
                .connect(GameConn.class, Transport.udp("localhost", port))
                .addService(GameService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(200);

        handler.latch = new CountDownLatch(1);
        client.send(new Ping(42));
        assertTrue(handler.latch.await(2, TimeUnit.SECONDS));
        assertEquals(42, handler.received.get(0).seq());

        client.close();
        server.close();
    }
}
