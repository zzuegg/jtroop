package jtroop.server;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ServerClientTest {

    // --- Connection types ---
    record GameConnection(int version) {
        record Accepted(int negotiatedVersion) {}
    }

    // --- Messages ---
    record Ping(int seq) {}
    record Pong(int seq) {}
    record ChatMsg(String text) {}

    // --- Service contract ---
    interface PingService {
        Pong ping(Ping p);
        void chat(ChatMsg msg);
    }

    // --- Handler ---
    @Handles(PingService.class)
    static class PingHandler {
        final CopyOnWriteArrayList<ChatMsg> chats = new CopyOnWriteArrayList<>();
        CountDownLatch chatLatch = new CountDownLatch(1);

        @OnMessage
        Pong ping(Ping p, ConnectionId sender) {
            return new Pong(p.seq());
        }

        @OnMessage
        void chat(ChatMsg msg, ConnectionId sender) {
            chats.add(msg);
            chatLatch.countDown();
        }
    }

    @Test
    void server_buildsAndStarts() throws Exception {
        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(PingHandler.class, GameConnection.class)
                .build();

        server.start();
        assertTrue(server.isRunning());
        server.close();
    }

    @Test
    void client_connectsToServer() throws Exception {
        var handler = new PingHandler();
        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .build();
        client.start();

        // Give time for connection
        Thread.sleep(200);
        assertTrue(client.isConnected(GameConnection.class));

        client.close();
        server.close();
    }

    @Test
    void client_sendsFireAndForget() throws Exception {
        var handler = new PingHandler();
        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(PingService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        client.send(new ChatMsg("hello"));

        assertTrue(handler.chatLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, handler.chats.size());
        assertEquals("hello", handler.chats.getFirst().text());

        client.close();
        server.close();
    }

    @Test
    void client_requestResponse() throws Exception {
        var handler = new PingHandler();
        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(PingService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(200);

        var response = client.request(new Ping(42), Pong.class);
        assertNotNull(response);
        assertEquals(42, response.seq());

        client.close();
        server.close();
    }

    @Test
    void multipleClients_independentCommunication() throws Exception {
        var handler = new PingHandler();
        handler.chatLatch = new CountDownLatch(2);

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client1 = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(PingService.class, GameConnection.class)
                .build();
        var client2 = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(PingService.class, GameConnection.class)
                .build();
        client1.start();
        client2.start();
        Thread.sleep(200);

        client1.send(new ChatMsg("from-1"));
        client2.send(new ChatMsg("from-2"));

        assertTrue(handler.chatLatch.await(2, TimeUnit.SECONDS));
        assertEquals(2, handler.chats.size());

        client1.close();
        client2.close();
        server.close();
    }
}
