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
class ConnectionLossTest {

    record Conn(int v) {}
    record Ping(int id) {}

    interface Svc { void ping(Ping p); }

    @Handles(Svc.class)
    static class LifecycleHandler {
        final CopyOnWriteArrayList<ConnectionId> connected = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<ConnectionId> disconnected = new CopyOnWriteArrayList<>();
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        CountDownLatch connectLatch = new CountDownLatch(1);

        @OnMessage void ping(Ping p, ConnectionId s) {}
        @OnConnect void join(ConnectionId id) { connected.add(id); connectLatch.countDown(); }
        @OnDisconnect void leave(ConnectionId id) { disconnected.add(id); disconnectLatch.countDown(); }
    }

    @Test
    void serverFiresDisconnect_whenClientCloses() throws Exception {
        var handler = new LifecycleHandler();
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
        assertEquals(1, handler.connected.size());

        // Client closes the connection
        client.close();

        assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS), "Server should detect client disconnect");
        assertEquals(1, handler.disconnected.size());
        assertEquals(handler.connected.getFirst(), handler.disconnected.getFirst());

        server.close();
    }

    @Test
    void serverFiresDisconnect_whenClientCrashes_abruptSocketClose() throws Exception {
        var handler = new LifecycleHandler();
        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        // Use raw SocketChannel and force-close without graceful shutdown
        var rawClient = java.nio.channels.SocketChannel.open();
        rawClient.connect(new java.net.InetSocketAddress("localhost", port));

        assertTrue(handler.connectLatch.await(2, TimeUnit.SECONDS));

        // Simulate crash: close without sending anything
        rawClient.socket().setSoLinger(true, 0); // RST on close
        rawClient.close();

        assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS), "Server should detect abrupt close");
        assertEquals(1, handler.disconnected.size());

        server.close();
    }

    @Test
    void multipleClients_disconnectsTrackedIndependently() throws Exception {
        var handler = new LifecycleHandler();
        handler.connectLatch = new CountDownLatch(3);
        handler.disconnectLatch = new CountDownLatch(2);

        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        var clients = new Client[3];
        for (int i = 0; i < 3; i++) {
            clients[i] = Client.builder()
                    .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(Svc.class, Conn.class)
                    .build();
            clients[i].start();
        }

        assertTrue(handler.connectLatch.await(3, TimeUnit.SECONDS));
        assertEquals(3, handler.connected.size());

        // Close 2 of 3
        clients[0].close();
        clients[2].close();

        assertTrue(handler.disconnectLatch.await(3, TimeUnit.SECONDS));
        assertEquals(2, handler.disconnected.size());

        // Third client still connected
        Thread.sleep(200);
        assertEquals(2, handler.disconnected.size());

        clients[1].close();
        server.close();
    }

    @Test
    void server_canManuallyCloseConnection() throws Exception {
        var handler = new LifecycleHandler();
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
        var connId = handler.connected.getFirst();

        // Server-side manual close (e.g. kick player)
        server.closeConnection(connId);

        assertTrue(handler.disconnectLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, handler.disconnected.size());

        client.close();
        server.close();
    }

    @Test
    void client_canCloseSpecificConnection() throws Exception {
        record Conn2(int v) {}
        var handler = new LifecycleHandler();
        handler.connectLatch = new CountDownLatch(2);
        handler.disconnectLatch = new CountDownLatch(1);

        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .listen(Conn2.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .addService(handler, Conn2.class)
                .build();
        server.start();

        var client = Client.builder()
                .connect(Conn.class, Transport.tcp("localhost", server.port(Conn.class)), new FramingLayer())
                .connect(Conn2.class, Transport.tcp("localhost", server.port(Conn2.class)), new FramingLayer())
                .addService(Svc.class, Conn.class)
                .addService(Svc.class, Conn2.class)
                .build();
        client.start();

        assertTrue(handler.connectLatch.await(2, TimeUnit.SECONDS));
        assertTrue(client.isConnected(Conn.class));
        assertTrue(client.isConnected(Conn2.class));

        // Close only one
        client.closeConnection(Conn.class);

        assertTrue(handler.disconnectLatch.await(2, TimeUnit.SECONDS));
        assertFalse(client.isConnected(Conn.class));
        assertTrue(client.isConnected(Conn2.class)); // other still up

        client.close();
        server.close();
    }

    @Test
    void sessionSlot_reusedAfterDisconnect() throws Exception {
        var handler = new LifecycleHandler();
        handler.connectLatch = new CountDownLatch(2);
        handler.disconnectLatch = new CountDownLatch(1);

        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        var client1 = Client.builder()
                .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(Svc.class, Conn.class)
                .build();
        client1.start();
        Thread.sleep(200);
        var id1 = handler.connected.getFirst();

        client1.close();
        assertTrue(handler.disconnectLatch.await(2, TimeUnit.SECONDS));

        // Reconnect — should get fresh ConnectionId (same index, different generation)
        var client2 = Client.builder()
                .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(Svc.class, Conn.class)
                .build();
        client2.start();

        assertTrue(handler.connectLatch.await(3, TimeUnit.SECONDS));
        var id2 = handler.connected.get(1);

        // Same slot index (reused) but different generation
        assertEquals(id1.index(), id2.index());
        assertNotEquals(id1.generation(), id2.generation());

        client2.close();
        server.close();
    }
}
