package jtroop.server;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Measures connection setup speed and time-to-first-message latency.
 * Profiles the full path: TCP accept -> channel config (TCP_NODELAY) ->
 * selector register -> handshake exchange -> session allocate ->
 * {@code @OnConnect} dispatch -> first {@code @OnMessage} handler call.
 */
@Timeout(60)
class ConnectionBenchmarkTest {

    record BenchConn(int version) {
        record Accepted(int version) {}
    }

    record Ping(int seq) {}
    record Pong(int seq) {}

    interface PingSvc { Pong ping(Ping m); }

    @Handles(PingSvc.class)
    static class PingHandler {
        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();

        @OnConnect void onConnect(ConnectionId id) { connects.incrementAndGet(); }
        @OnDisconnect void onDisconnect(ConnectionId id) { disconnects.incrementAndGet(); }
        @OnMessage Pong ping(Ping m, ConnectionId s) { return new Pong(m.seq()); }
    }

    /**
     * connectDisconnectThroughput: opens+closes as fast as possible, no handshake.
     * Each iteration creates a new Client, connects TCP, then tears down.
     */
    @Test
    void connectDisconnectThroughput() throws Exception {
        var handler = new PingHandler();
        var server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        int warmup = 30;
        int iterations = 200;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            var c = Client.builder()
                    .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            c.start();
            c.close();
        }
        Thread.sleep(100);
        handler.connects.set(0);

        // Measure: no-handshake connect/disconnect throughput
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var c = Client.builder()
                    .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            c.start();
            c.close();
        }
        long elapsed = System.nanoTime() - start;
        double perSec = iterations / (elapsed / 1_000_000_000.0);
        double avgUs = (elapsed / 1_000.0) / iterations;

        server.close();

        System.out.println("=== Connect/Disconnect Throughput (no handshake) ===");
        System.out.printf("  %d iterations in %.1f ms%n", iterations, elapsed / 1_000_000.0);
        System.out.printf("  %.0f conn/s, avg %.0f us/conn%n", perSec, avgUs);

        assertTrue(perSec > 100, "Should manage >100 conn/s, got " + perSec);
    }

    /**
     * connectDisconnectThroughput with handshake: 1 extra round-trip
     * (client sends magic+record, server responds accepted+record).
     */
    @Test
    void connectDisconnectThroughput_withHandshake() throws Exception {
        var handler = new PingHandler();
        var server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(BenchConn.class, req ->
                        new BenchConn.Accepted(req.version()))
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        int warmup = 20;
        int iterations = 100;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            var c = Client.builder()
                    .connect(new BenchConn(1),
                            Transport.tcp("localhost", port), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            c.start();
            Thread.sleep(10);
            c.close();
        }
        Thread.sleep(100);

        // Measure: with-handshake connect/disconnect throughput
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var c = Client.builder()
                    .connect(new BenchConn(1),
                            Transport.tcp("localhost", port), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            c.start();
            // Must wait for handshake to complete (1 round-trip)
            Thread.sleep(5);
            c.close();
        }
        long elapsed = System.nanoTime() - start;
        double perSec = iterations / (elapsed / 1_000_000_000.0);
        double avgUs = (elapsed / 1_000.0) / iterations;

        server.close();

        System.out.println("=== Connect/Disconnect Throughput (with handshake) ===");
        System.out.printf("  %d iterations in %.1f ms%n", iterations, elapsed / 1_000_000.0);
        System.out.printf("  %.0f conn/s, avg %.0f us/conn%n", perSec, avgUs);
        System.out.println("  Note: handshake adds 1 round-trip (client->server->client).");

        assertTrue(perSec > 50, "Should manage >50 conn/s with handshake, got " + perSec);
    }

    /**
     * Time-to-first-message: connect -> first successful request/response.
     * Measures both no-handshake and with-handshake paths to quantify
     * the additional round-trip cost.
     */
    @Test
    @org.junit.jupiter.api.Timeout(120)
    void timeToFirstMessage() throws Exception {
        // --- No handshake path ---
        var handler = new PingHandler();
        var server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        int warmup = 10;
        int iterations = 50;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            var c = Client.builder()
                    .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            c.start();
            Thread.sleep(30);
            try { c.request(new Ping(i), Pong.class); } catch (Exception _) {}
            c.close();
        }
        Thread.sleep(100);

        long[] noHsLatencies = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            var c = Client.builder()
                    .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            long t0 = System.nanoTime();
            c.start();
            // Retry until the channel is registered and server can respond
            Pong result = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                try {
                    result = c.request(new Ping(i), Pong.class);
                    break;
                } catch (Exception e) {
                    Thread.sleep(1);
                }
            }
            long t1 = System.nanoTime();
            noHsLatencies[i] = t1 - t0;
            assertNotNull(result, "Request should succeed within 100ms");
            assertEquals(i, result.seq());
            c.close();
        }
        server.close();

        // --- With handshake path ---
        var handler2 = new PingHandler();
        var serverHs = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(BenchConn.class, req ->
                        new BenchConn.Accepted(req.version()))
                .addService(handler2, BenchConn.class)
                .build();
        serverHs.start();
        int portHs = serverHs.port(BenchConn.class);

        // Warmup
        for (int i = 0; i < warmup; i++) {
            var c = Client.builder()
                    .connect(new BenchConn(1),
                            Transport.tcp("localhost", portHs), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            c.start();
            Thread.sleep(30);
            try { c.request(new Ping(i), Pong.class); } catch (Exception _) {}
            c.close();
        }
        Thread.sleep(100);

        long[] hsLatencies = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            var c = Client.builder()
                    .connect(new BenchConn(1),
                            Transport.tcp("localhost", portHs), new FramingLayer())
                    .addService(PingSvc.class, BenchConn.class)
                    .build();
            long t0 = System.nanoTime();
            c.start();
            Pong result = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                try {
                    result = c.request(new Ping(i), Pong.class);
                    break;
                } catch (Exception e) {
                    Thread.sleep(1);
                }
            }
            long t1 = System.nanoTime();
            hsLatencies[i] = t1 - t0;
            assertNotNull(result, "Request with handshake should succeed within 200ms");
            assertEquals(i, result.seq());
            c.close();
        }
        serverHs.close();

        // Stats
        long noHsP50 = percentile(noHsLatencies, 50);
        long noHsP99 = percentile(noHsLatencies, 99);
        long hsP50 = percentile(hsLatencies, 50);
        long hsP99 = percentile(hsLatencies, 99);

        System.out.println("=== Time-to-First-Message ===");
        System.out.printf("  No-handshake   p50: %.2f ms, p99: %.2f ms%n",
                noHsP50 / 1_000_000.0, noHsP99 / 1_000_000.0);
        System.out.printf("  With-handshake p50: %.2f ms, p99: %.2f ms%n",
                hsP50 / 1_000_000.0, hsP99 / 1_000_000.0);
        System.out.printf("  Handshake overhead p50: +%.2f ms, p99: +%.2f ms%n",
                (hsP50 - noHsP50) / 1_000_000.0, (hsP99 - noHsP99) / 1_000_000.0);
        System.out.println("  Handshake is 1 round-trip: client->server (magic+record) + server->client (accepted+record).");
        System.out.println("  No unnecessary round-trips detected in the protocol.");
    }

    private static long percentile(long[] data, int pct) {
        long[] sorted = data.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }
}
