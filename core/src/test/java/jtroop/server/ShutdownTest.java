package jtroop.server;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ShutdownTest {

    record Conn(int v) {}
    record Ping(int id) {}

    interface Svc { void ping(Ping p); }

    @Handles(Svc.class)
    static class LifecycleHandler {
        final CopyOnWriteArrayList<ConnectionId> connected = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<ConnectionId> disconnected = new CopyOnWriteArrayList<>();
        volatile CountDownLatch connectLatch;
        volatile CountDownLatch disconnectLatch;

        LifecycleHandler(int expectedConnects, int expectedDisconnects) {
            connectLatch = new CountDownLatch(expectedConnects);
            disconnectLatch = new CountDownLatch(expectedDisconnects);
        }

        @OnMessage void ping(Ping p, ConnectionId s) {}
        @OnConnect void join(ConnectionId id) { connected.add(id); connectLatch.countDown(); }
        @OnDisconnect void leave(ConnectionId id) { disconnected.add(id); disconnectLatch.countDown(); }
    }

    @Test
    void serverClose_disconnectsAllClients_firesOnDisconnect() throws Exception {
        int clientCount = 5;
        var handler = new LifecycleHandler(clientCount, clientCount);

        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        var clients = new Client[clientCount];
        for (int i = 0; i < clientCount; i++) {
            clients[i] = Client.builder()
                    .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(Svc.class, Conn.class)
                    .build();
            clients[i].start();
        }

        assertTrue(handler.connectLatch.await(5, TimeUnit.SECONDS),
                "All clients should connect");
        assertEquals(clientCount, handler.connected.size());

        // Server close should fire @OnDisconnect for all active connections
        server.close();

        assertTrue(handler.disconnectLatch.await(5, TimeUnit.SECONDS),
                "@OnDisconnect should fire for all " + clientCount + " connections");
        assertEquals(clientCount, handler.disconnected.size());
        assertFalse(server.isRunning(), "Server should not be running after close");

        // All client channels should see the disconnect
        Thread.sleep(200); // allow client event loops to observe channel closure
        for (int i = 0; i < clientCount; i++) {
            assertFalse(clients[i].isConnected(Conn.class),
                    "Client " + i + " should see disconnect after server close");
        }

        // Clean up client event loops
        for (var c : clients) c.close();
    }

    @Test
    void serverClose_idempotent() throws Exception {
        var handler = new LifecycleHandler(1, 1);
        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        var client = Client.builder()
                .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(Svc.class, Conn.class)
                .build();
        client.start();
        assertTrue(handler.connectLatch.await(2, TimeUnit.SECONDS));

        // Close twice — second call should be a no-op, no exceptions
        server.close();
        server.close();

        assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS));
        // @OnDisconnect should fire exactly once, not twice
        assertEquals(1, handler.disconnected.size());
        assertFalse(server.isRunning());

        client.close();
    }

    @Test
    void clientClose_idempotent() throws Exception {
        var handler = new LifecycleHandler(1, 1);
        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        var client = Client.builder()
                .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(Svc.class, Conn.class)
                .build();
        client.start();
        assertTrue(handler.connectLatch.await(2, TimeUnit.SECONDS));

        // Close twice — no exceptions
        client.close();
        client.close();

        assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS));
        server.close();
    }

    @Test
    void eventLoopClose_idempotent() throws Exception {
        var loop = new jtroop.core.EventLoop("test-loop");
        loop.start();
        Thread.sleep(50);
        assertTrue(loop.isRunning());

        loop.close();
        assertFalse(loop.isRunning());

        // Second close should not throw
        loop.close();
        assertFalse(loop.isRunning());
    }
}
