package net.server;

import net.client.Client;
import net.pipeline.layers.FramingLayer;
import net.service.*;
import net.session.ConnectionId;
import net.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ExecutorTest {

    record Conn(int v) {}
    record Msg(int id) {}
    record Reply(int id, String thread) {}

    interface Svc { Reply handle(Msg m); }

    @Handles(Svc.class)
    static class Handler {
        @OnMessage Reply handle(Msg m, ConnectionId s) {
            return new Reply(m.id(), Thread.currentThread().getName());
        }
    }

    @Test
    void virtualThreadExecutor_dispatchesOnVirtualThread() throws Exception {
        var handler = new Handler();
        var server = Server.builder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
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
        Thread.sleep(300);

        var reply = client.request(new Msg(1), Reply.class);
        assertNotNull(reply);
        assertEquals(1, reply.id());
        // Virtual threads have names like "" or contain "virtual"
        assertNotEquals("server-loop", reply.thread(), "Should not run on EventLoop thread");

        client.close();
        server.close();
    }

    @Test
    void defaultExecutor_dispatchesInline() throws Exception {
        var handler = new Handler();
        var server = Server.builder()
                // No executor — uses inline Runnable::run
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
        Thread.sleep(300);

        var reply = client.request(new Msg(2), Reply.class);
        assertNotNull(reply);
        assertEquals(2, reply.id());
        // Default executor runs inline on EventLoop thread
        assertEquals("server-loop", reply.thread());

        client.close();
        server.close();
    }

    @Test
    void virtualThreads_concurrentRequests() throws Exception {
        var handler = new Handler();
        var server = Server.builder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        server.start();
        int port = server.port(Conn.class);

        int numClients = 5;
        var replies = new CopyOnWriteArrayList<Reply>();
        var latch = new CountDownLatch(numClients);

        for (int i = 0; i < numClients; i++) {
            final int idx = i;
            var client = Client.builder()
                    .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(Svc.class, Conn.class)
                    .build();
            client.start();
            Thread.sleep(100);
            new Thread(() -> {
                try {
                    var reply = client.request(new Msg(idx), Reply.class);
                    replies.add(reply);
                    latch.countDown();
                    client.close();
                } catch (Exception e) { latch.countDown(); }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(numClients, replies.size());

        server.close();
    }
}
