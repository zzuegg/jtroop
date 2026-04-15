package net.service;

import net.client.Client;
import net.pipeline.layers.FramingLayer;
import net.server.Server;
import net.session.ConnectionId;
import net.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BroadcastUnicastTest {

    record TestConn(int v) {}
    record ChatMsg(String text) {}
    record PushMsg(String data) {}

    interface ChatSvc {
        void send(ChatMsg msg);
    }

    @Handles(ChatSvc.class)
    static class ChatBroadcastHandler {
        @OnMessage
        void send(ChatMsg msg, ConnectionId sender, Broadcast broadcast) {
            broadcast.send(new PushMsg("echo:" + msg.text()));
        }
    }

    @Handles(ChatSvc.class)
    static class ChatUnicastHandler {
        @OnMessage
        void send(ChatMsg msg, ConnectionId sender, Unicast unicast) {
            unicast.send(sender, new PushMsg("reply:" + msg.text()));
        }
    }

    @Test
    void broadcast_sendsToAllConnectedClients() throws Exception {
        var handler = new ChatBroadcastHandler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var received1 = new CopyOnWriteArrayList<Record>();
        var received2 = new CopyOnWriteArrayList<Record>();
        var latch = new CountDownLatch(2);

        var client1 = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(ChatSvc.class, TestConn.class)
                .onMessage(PushMsg.class, msg -> { received1.add(msg); latch.countDown(); })
                .build();
        var client2 = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(ChatSvc.class, TestConn.class)
                .onMessage(PushMsg.class, msg -> { received2.add(msg); latch.countDown(); })
                .build();
        client1.start();
        client2.start();
        Thread.sleep(300);

        // Client1 sends, both should receive broadcast
        client1.send(new ChatMsg("hello"));
        assertTrue(latch.await(3, TimeUnit.SECONDS));

        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
        assertEquals("echo:hello", ((PushMsg) received1.getFirst()).data());

        client1.close();
        client2.close();
        server.close();
    }

    @Test
    void unicast_sendsOnlyToSender() throws Exception {
        var handler = new ChatUnicastHandler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var received1 = new CopyOnWriteArrayList<Record>();
        var received2 = new CopyOnWriteArrayList<Record>();
        var latch = new CountDownLatch(1);

        var client1 = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(ChatSvc.class, TestConn.class)
                .onMessage(PushMsg.class, msg -> { received1.add(msg); latch.countDown(); })
                .build();
        var client2 = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(ChatSvc.class, TestConn.class)
                .onMessage(PushMsg.class, msg -> received2.add(msg))
                .build();
        client1.start();
        client2.start();
        Thread.sleep(300);

        client1.send(new ChatMsg("hello"));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(200); // give time for potential message to client2

        assertEquals(1, received1.size());
        assertEquals(0, received2.size());
        assertEquals("reply:hello", ((PushMsg) received1.getFirst()).data());

        client1.close();
        client2.close();
        server.close();
    }
}
