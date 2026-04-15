package jtroop.client;

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

@Timeout(10)
class ServiceProxyTest {

    record TestConn(int v) {}
    record ChatMsg(String text) {}
    record ChatAck(int ok) {}
    record MoveMsg(float x) {}

    interface ChatService {
        ChatAck send(ChatMsg msg);
        void fire(MoveMsg msg);
    }

    @Handles(ChatService.class)
    static class Handler {
        final CopyOnWriteArrayList<ChatMsg> chats = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<MoveMsg> moves = new CopyOnWriteArrayList<>();
        CountDownLatch moveLatch = new CountDownLatch(1);

        @OnMessage ChatAck send(ChatMsg msg, ConnectionId s) {
            chats.add(msg);
            return new ChatAck(1);
        }
        @OnMessage void fire(MoveMsg msg, ConnectionId s) {
            moves.add(msg);
            moveLatch.countDown();
        }
    }

    @Test
    void serviceProxy_requestResponse() throws Exception {
        var handler = new Handler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(ChatService.class, TestConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Get typed proxy
        ChatService chat = client.service(ChatService.class);
        assertNotNull(chat);

        // Request/response through proxy
        var ack = chat.send(new ChatMsg("hello"));
        assertNotNull(ack);
        assertEquals(1, ack.ok());
        assertEquals("hello", handler.chats.getFirst().text());

        client.close();
        server.close();
    }

    @Test
    void serviceProxy_fireAndForget() throws Exception {
        var handler = new Handler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(ChatService.class, TestConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        ChatService chat = client.service(ChatService.class);
        chat.fire(new MoveMsg(3.14f));

        assertTrue(handler.moveLatch.await(2, TimeUnit.SECONDS));
        assertEquals(3.14f, handler.moves.getFirst().x());

        client.close();
        server.close();
    }

    @Test
    void serviceProxy_sameInstanceReturnedTwice() throws Exception {
        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", 1), new FramingLayer())
                .addService(ChatService.class, TestConn.class)
                .build();

        ChatService p1 = client.service(ChatService.class);
        ChatService p2 = client.service(ChatService.class);
        assertSame(p1, p2);

        client.close();
    }
}
