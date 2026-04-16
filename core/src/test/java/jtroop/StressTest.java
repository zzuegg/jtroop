package jtroop;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests measuring sustained throughput with many clients under load,
 * similar to wrk/wrk2 style testing of Netty. Each test measures messages
 * ACTUALLY received by the server (not just sent by clients).
 */
@Timeout(60)
class StressTest {

    record GameConn(int v) {}
    public record PositionUpdate(float x, float y, float z, float yaw) {}

    interface GameSvc {
        void position(PositionUpdate p);
    }

    @Handles(GameSvc.class)
    public static class CountingHandler {
        final AtomicInteger received = new AtomicInteger(0);
        final AtomicInteger connected = new AtomicInteger(0);
        final AtomicInteger disconnected = new AtomicInteger(0);

        @OnMessage void position(PositionUpdate p, ConnectionId s) {
            received.incrementAndGet();
        }

        @OnConnect void join(ConnectionId id) { connected.incrementAndGet(); }
        @OnDisconnect void leave(ConnectionId id) { disconnected.incrementAndGet(); }
    }

    @Test
    void stress_100_clients_sustained_load() throws Exception {
        var handler = new CountingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .eventLoops(2)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        int numClients = 100;
        int messagesPerClient = 100;
        var clients = new Client[numClients];
        for (int i = 0; i < numClients; i++) {
            clients[i] = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameSvc.class, GameConn.class)
                    .build();
            clients[i].start();
        }

        // Wait for all connections
        long deadline = System.currentTimeMillis() + 5000;
        while (handler.connected.get() < numClients && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(numClients, handler.connected.get(), "Not all clients connected");

        // Send load concurrently from all clients
        var sendStart = new CountDownLatch(1);
        var sendDone = new CountDownLatch(numClients);
        for (int i = 0; i < numClients; i++) {
            final var client = clients[i];
            new Thread(() -> {
                try {
                    sendStart.await();
                    for (int j = 0; j < messagesPerClient; j++) {
                        client.send(new PositionUpdate(j, j * 2, j * 3, 0));
                    }
                    client.flush();
                } catch (Exception _) {}
                finally { sendDone.countDown(); }
            }).start();
        }

        long t0 = System.nanoTime();
        sendStart.countDown();
        assertTrue(sendDone.await(30, TimeUnit.SECONDS), "Send phase timed out");

        // Wait for all messages to be received
        int expected = numClients * messagesPerClient;
        deadline = System.currentTimeMillis() + 10000;
        while (handler.received.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        long elapsed = System.nanoTime() - t0;

        assertEquals(expected, handler.received.get(),
                "Expected " + expected + " messages, got " + handler.received.get());

        double elapsedMs = elapsed / 1_000_000.0;
        double throughput = expected / (elapsedMs / 1000);
        System.out.printf("Stress result: %d clients × %d msgs = %d msgs in %.1fms = %.0f msg/s%n",
                numClients, messagesPerClient, expected, elapsedMs, throughput);

        for (var c : clients) c.close();
        server.close();
    }

    @Test
    void stress_rapid_connect_disconnect() throws Exception {
        var handler = new CountingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        int cycles = 50;
        for (int i = 0; i < cycles; i++) {
            var c = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameSvc.class, GameConn.class)
                    .build();
            c.start();
            c.send(new PositionUpdate(i, 0, 0, 0));
            c.flush();
            Thread.sleep(10);
            c.close();
        }

        // Wait for server to process all
        long deadline = System.currentTimeMillis() + 5000;
        while ((handler.connected.get() < cycles || handler.disconnected.get() < cycles)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(cycles, handler.connected.get(), "Missing connects");
        assertEquals(cycles, handler.disconnected.get(), "Missing disconnects");
        assertEquals(cycles, handler.received.get(), "Missing messages");

        server.close();
    }

    @Test
    void stress_single_client_sustained_throughput() throws Exception {
        var handler = new CountingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        var client = Client.builder()
                .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameSvc.class, GameConn.class)
                .build();
        client.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (handler.connected.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        int messages = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < messages; i++) {
            client.send(new PositionUpdate(i, i * 2, i * 3, 0));
        }
        client.flush();

        // Wait until received
        deadline = System.currentTimeMillis() + 15000;
        while (handler.received.get() < messages && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        long elapsed = System.nanoTime() - t0;

        assertEquals(messages, handler.received.get(),
                "Lost messages: sent " + messages + ", received " + handler.received.get());

        double elapsedMs = elapsed / 1_000_000.0;
        double throughput = messages / (elapsedMs / 1000);
        System.out.printf("Single-client throughput: %d msgs in %.1fms = %.0f msg/s%n",
                messages, elapsedMs, throughput);

        client.close();
        server.close();
    }

    @Test
    void stress_zero_message_loss_under_load() throws Exception {
        var handler = new CountingHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        int numClients = 20;
        int messagesPerClient = 1000;
        var clients = new Client[numClients];
        for (int i = 0; i < numClients; i++) {
            clients[i] = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameSvc.class, GameConn.class)
                    .build();
            clients[i].start();
        }

        long deadline = System.currentTimeMillis() + 3000;
        while (handler.connected.get() < numClients && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        // Each client sends sequential IDs so we can verify nothing is lost
        for (int i = 0; i < numClients; i++) {
            final int clientIdx = i;
            final var client = clients[i];
            new Thread(() -> {
                for (int j = 0; j < messagesPerClient; j++) {
                    client.send(new PositionUpdate(clientIdx, j, 0, 0));
                }
                client.flush();
            }).start();
        }

        int expected = numClients * messagesPerClient;
        deadline = System.currentTimeMillis() + 30000;
        while (handler.received.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertEquals(expected, handler.received.get(), "TCP should guarantee no message loss");

        for (var c : clients) c.close();
        server.close();
    }
}
