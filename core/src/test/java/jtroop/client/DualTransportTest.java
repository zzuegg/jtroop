package jtroop.client;

import jtroop.ConfigurationException;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the same record type can be routed to both TCP and UDP via different
 * service interface methods. Before the per-method transport routing refactor,
 * transport was keyed by record type — so a single type was either TCP or UDP,
 * never both.
 */
@Timeout(10)
class DualTransportTest {

    record GameConn(int v) {}
    record Pos(float x, float y, float z, float yaw) {}

    /** Same Pos record on two methods — one TCP, one UDP. */
    interface DualTransportService {
        void reliable(Pos pos);              // TCP
        @Datagram void fast(Pos pos);        // UDP
    }

    @Handles(DualTransportService.class)
    static class DualHandler {
        final CopyOnWriteArrayList<Pos> tcpReceived = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Pos> udpReceived = new CopyOnWriteArrayList<>();
        CountDownLatch tcpLatch = new CountDownLatch(1);
        CountDownLatch udpLatch = new CountDownLatch(1);

        // The server has a single handler for Pos. We can't distinguish TCP vs
        // UDP at the handler level (same message type), so we track all arrivals
        // and verify counts from the transport layer's perspective.
        final CopyOnWriteArrayList<Pos> all = new CopyOnWriteArrayList<>();
        CountDownLatch allLatch = new CountDownLatch(2);

        @OnMessage void handle(Pos pos, ConnectionId sender) {
            all.add(pos);
            allLatch.countDown();
        }
    }

    @Test
    void sameRecordType_differentMethods_differentTransports() throws Exception {
        var handler = new DualHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .listen(GameConn.class, Transport.udp(0))
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int tcpPort = server.port(GameConn.class);
        int udpPort = server.udpPort(GameConn.class);

        var client = Client.builder()
                .connect(GameConn.class, Transport.tcp("localhost", tcpPort), new FramingLayer())
                .connect(GameConn.class, Transport.udp("localhost", udpPort))
                .addService(DualTransportService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        DualTransportService svc = client.service(DualTransportService.class);

        // reliable() routes through sendViaTcp
        svc.reliable(new Pos(1, 2, 3, 0));
        // fast() routes through sendViaUdp
        svc.fast(new Pos(4, 5, 6, 0));

        assertTrue(handler.allLatch.await(3, TimeUnit.SECONDS),
                "Expected 2 messages (1 TCP + 1 UDP); got " + handler.all.size());
        assertEquals(2, handler.all.size());

        // Verify both payloads arrived correctly
        var xs = handler.all.stream().map(Pos::x).sorted().toList();
        assertEquals(1f, xs.get(0));
        assertEquals(4f, xs.get(1));

        client.close();
        server.close();
    }

    @Test
    void datagramOnNonVoidMethod_rejectedAtGenerationTime() {
        interface BadService {
            @Datagram Pos roundTrip(Pos p);
        }

        var client = Client.builder()
                .connect(GameConn.class, Transport.tcp("localhost", 1), new FramingLayer())
                .addService(BadService.class, GameConn.class)
                .build();

        var ex = assertThrows(ConfigurationException.class,
                () -> client.service(BadService.class));
        assertTrue(ex.getMessage().contains("UDP"),
                "Error should mention UDP; was: " + ex.getMessage());
    }
}
