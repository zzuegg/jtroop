package jtroop.testing;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ForwarderTest {

    record TestConnection(int version) {
        record Accepted(int version) {}
    }

    record EchoMsg(int seq) {}
    record EchoReply(int seq) {}
    record FireMsg(int seq) {}

    interface EchoService {
        EchoReply echo(EchoMsg msg);
        void fire(FireMsg msg);
    }

    @Handles(EchoService.class)
    static class EchoHandler {
        final CopyOnWriteArrayList<Object> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        @OnMessage
        EchoReply echo(EchoMsg msg, ConnectionId sender) {
            received.add(msg);
            return new EchoReply(msg.seq());
        }

        @OnMessage
        void fire(FireMsg msg, ConnectionId sender) {
            received.add(msg);
            latch.countDown();
        }
    }

    @Test
    void forwarder_passesTrafficThrough() throws Exception {
        var handler = new EchoHandler();
        var server = Server.builder()
                .listen(TestConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConnection.class)
                .build();
        server.start();
        int serverPort = server.port(TestConnection.class);

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(TestConnection.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                .addService(EchoService.class, TestConnection.class)
                .build();
        client.start();
        Thread.sleep(500);

        var reply = client.request(new EchoMsg(42), EchoReply.class);
        assertEquals(42, reply.seq());

        client.close();
        forwarder.close();
        server.close();
    }

    @Test
    void forwarder_addsLatency() throws Exception {
        var handler = new EchoHandler();
        var server = Server.builder()
                .listen(TestConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConnection.class)
                .build();
        server.start();
        int serverPort = server.port(TestConnection.class);

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                    .latency(Duration.ofMillis(100), Duration.ofMillis(200))
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(TestConnection.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                .addService(EchoService.class, TestConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        long start = System.currentTimeMillis();
        var reply = client.request(new EchoMsg(1), EchoReply.class);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, reply.seq());
        // Should be at least 100ms due to latency injection (applies to at least one direction)
        assertTrue(elapsed >= 80, "Expected >= 80ms, got " + elapsed + "ms");

        client.close();
        forwarder.close();
        server.close();
    }

    @Test
    void forwarder_dropsPackets() throws Exception {
        var handler = new EchoHandler();
        handler.latch = new CountDownLatch(50);

        var server = Server.builder()
                .listen(TestConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConnection.class)
                .build();
        server.start();
        int serverPort = server.port(TestConnection.class);

        // 50% drop rate — very aggressive, should drop roughly half
        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                    .packetLoss(0.50)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(TestConnection.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                .addService(EchoService.class, TestConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Send messages in bursts with gaps so they span multiple TCP segments
        for (int burst = 0; burst < 10; burst++) {
            for (int i = 0; i < 10; i++) {
                client.send(new FireMsg(burst * 10 + i));
            }
            client.flush();
            Thread.sleep(20); // gap between bursts → separate TCP segments
        }

        Thread.sleep(1000);

        // With 50% drop rate per TCP read, expect some bursts dropped and some passed
        int receivedCount = handler.received.size();
        assertTrue(receivedCount < 95, "Expected some drops, got " + receivedCount + "/100");
        assertTrue(receivedCount > 5, "Expected some to arrive, got " + receivedCount + "/100");

        client.close();
        forwarder.close();
        server.close();
    }

    @Test
    void forwarder_cleanForwardNolmpairment() throws Exception {
        var handler = new EchoHandler();
        handler.latch = new CountDownLatch(10);

        var server = Server.builder()
                .listen(TestConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConnection.class)
                .build();
        server.start();
        int serverPort = server.port(TestConnection.class);

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(TestConnection.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                .addService(EchoService.class, TestConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        for (int i = 0; i < 10; i++) {
            client.send(new FireMsg(i));
        }

        assertTrue(handler.latch.await(3, TimeUnit.SECONDS));
        assertEquals(10, handler.received.size());

        client.close();
        forwarder.close();
        server.close();
    }

    @Test
    void forwarder_multipleForwards() throws Exception {
        var handler1 = new EchoHandler();
        var handler2 = new EchoHandler();

        var server1 = Server.builder()
                .listen(TestConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler1, TestConnection.class)
                .build();
        server1.start();

        var server2 = Server.builder()
                .listen(TestConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler2, TestConnection.class)
                .build();
        server2.start();

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", server1.port(TestConnection.class))
                .forward(Transport.tcp(0), "localhost", server2.port(TestConnection.class))
                .build();
        forwarder.start();

        // Connect to first forwarded port → server1
        var client1 = Client.builder()
                .connect(TestConnection.class, Transport.tcp("localhost", forwarder.port(0)), new FramingLayer())
                .addService(EchoService.class, TestConnection.class)
                .build();
        client1.start();
        Thread.sleep(200);

        client1.send(new FireMsg(1));
        assertTrue(handler1.latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, handler1.received.size());
        assertEquals(0, handler2.received.size());

        client1.close();
        forwarder.close();
        server1.close();
        server2.close();
    }
}
