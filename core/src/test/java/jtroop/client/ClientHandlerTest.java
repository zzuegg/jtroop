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
class ClientHandlerTest {

    record TestConn(int v) {}
    record PingMsg(String data) {}
    record PushMsg(String payload) {}
    record PositionUpdate(float x, float y) {}

    // Service interface: client sends PingMsg, server broadcasts PushMsg/PositionUpdate back
    interface GameService {
        void ping(PingMsg msg);
    }

    // Server handler: receives PingMsg, broadcasts PushMsg and PositionUpdate
    @Handles(GameService.class)
    static class ServerHandler {
        @OnMessage void ping(PingMsg msg, ConnectionId sender, Broadcast broadcast) {
            broadcast.send(new PushMsg(msg.data()));
            broadcast.send(new PositionUpdate(1.0f, 2.0f));
        }
    }

    // Client handler: receives server-sent messages
    @Handles(GameService.class)
    static class ClientHandler {
        final CopyOnWriteArrayList<PushMsg> pushes = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<PositionUpdate> positions = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<ConnectionId> connectIds = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<ConnectionId> disconnectIds = new CopyOnWriteArrayList<>();
        CountDownLatch pushLatch = new CountDownLatch(1);
        CountDownLatch posLatch = new CountDownLatch(1);
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        @OnMessage void onPush(PushMsg push) {
            pushes.add(push);
            pushLatch.countDown();
        }

        @OnMessage void onPosition(PositionUpdate pos, ConnectionId sender) {
            positions.add(pos);
            posLatch.countDown();
        }

        @OnConnect void connected(ConnectionId id) {
            connectIds.add(id);
            connectLatch.countDown();
        }

        @OnDisconnect void disconnected(ConnectionId id) {
            disconnectIds.add(id);
            disconnectLatch.countDown();
        }
    }

    @Handles(GameService.class)
    static class SecondClientHandler {
        final CopyOnWriteArrayList<PositionUpdate> positions = new CopyOnWriteArrayList<>();
        CountDownLatch posLatch = new CountDownLatch(1);

        @OnMessage void onPosition(PositionUpdate pos) {
            positions.add(pos);
            posLatch.countDown();
        }
    }

    @Test
    void onMessage_dispatchedToHandler() throws Exception {
        var serverHandler = new ServerHandler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(serverHandler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var clientHandler = new ClientHandler();
        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, TestConn.class)
                .addHandler(clientHandler, TestConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Client sends PingMsg → server broadcasts PushMsg + PositionUpdate back
        client.send(new PingMsg("hello"));
        assertTrue(clientHandler.pushLatch.await(3, TimeUnit.SECONDS),
                "Client handler should receive PushMsg");
        assertTrue(clientHandler.posLatch.await(3, TimeUnit.SECONDS),
                "Client handler should receive PositionUpdate");
        assertEquals("hello", clientHandler.pushes.getFirst().payload());
        assertEquals(1.0f, clientHandler.positions.getFirst().x());
        assertEquals(2.0f, clientHandler.positions.getFirst().y());

        client.close();
        server.close();
    }

    @Test
    void onConnect_firesOnConnection() throws Exception {
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var clientHandler = new ClientHandler();
        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addHandler(clientHandler, TestConn.class)
                .build();
        client.start();

        assertTrue(clientHandler.connectLatch.await(3, TimeUnit.SECONDS),
                "@OnConnect should fire after TCP connect");
        assertEquals(1, clientHandler.connectIds.size());
        assertTrue(clientHandler.connectIds.getFirst().isValid());

        client.close();
        server.close();
    }

    @Test
    void onDisconnect_firesOnClose() throws Exception {
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var clientHandler = new ClientHandler();
        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addHandler(clientHandler, TestConn.class)
                .build();
        client.start();
        assertTrue(clientHandler.connectLatch.await(3, TimeUnit.SECONDS));

        client.close();
        assertTrue(clientHandler.disconnectLatch.await(3, TimeUnit.SECONDS),
                "@OnDisconnect should fire on close()");
        assertEquals(1, clientHandler.disconnectIds.size());

        server.close();
    }

    @Test
    void multipleHandlers_allReceive() throws Exception {
        var serverHandler = new ServerHandler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(serverHandler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var handler1 = new ClientHandler();
        var handler2 = new SecondClientHandler();
        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, TestConn.class)
                .addHandler(handler1, TestConn.class)
                .addHandler(handler2, TestConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        client.send(new PingMsg("multi"));

        assertTrue(handler1.posLatch.await(3, TimeUnit.SECONDS),
                "First handler should receive PositionUpdate");
        assertTrue(handler2.posLatch.await(3, TimeUnit.SECONDS),
                "Second handler should receive PositionUpdate");
        assertEquals(1.0f, handler1.positions.getFirst().x());
        assertEquals(1.0f, handler2.positions.getFirst().x());

        client.close();
        server.close();
    }

    @Test
    void handlerWithInjectables_receivesConnectionId() throws Exception {
        var serverHandler = new ServerHandler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(serverHandler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        var clientHandler = new ClientHandler();
        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, TestConn.class)
                .addHandler(clientHandler, TestConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // @OnConnect should have already fired with a valid ConnectionId
        assertTrue(clientHandler.connectLatch.await(3, TimeUnit.SECONDS));
        assertTrue(clientHandler.connectIds.getFirst().isValid());

        // Send triggers server broadcast; handler receives with ConnectionId injectable
        client.send(new PingMsg("inject"));
        assertTrue(clientHandler.posLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1.0f, clientHandler.positions.getFirst().x());

        client.close();
        server.close();
    }

    @Test
    void existingOnMessage_worksAlongsideAddHandler() throws Exception {
        var serverHandler = new ServerHandler();
        var server = Server.builder()
                .listen(TestConn.class, Transport.tcp(0), new FramingLayer())
                .addService(serverHandler, TestConn.class)
                .build();
        server.start();
        int port = server.port(TestConn.class);

        // Use both onMessage lambda AND addHandler
        var lambdaPushes = new CopyOnWriteArrayList<PushMsg>();
        var lambdaLatch = new CountDownLatch(1);
        var clientHandler = new ClientHandler();

        var client = Client.builder()
                .connect(TestConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, TestConn.class)
                .onMessage(PushMsg.class, push -> {
                    lambdaPushes.add(push);
                    lambdaLatch.countDown();
                })
                .addHandler(clientHandler, TestConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // PushMsg should go to the lambda (messageHandlers takes priority)
        // PositionUpdate should go to the handler (no lambda registered for it)
        client.send(new PingMsg("compat"));

        assertTrue(lambdaLatch.await(3, TimeUnit.SECONDS),
                "onMessage lambda should receive PushMsg");
        assertEquals("compat", lambdaPushes.getFirst().payload());

        assertTrue(clientHandler.posLatch.await(3, TimeUnit.SECONDS),
                "Handler should receive PositionUpdate");
        assertEquals(1.0f, clientHandler.positions.getFirst().x());

        client.close();
        server.close();
    }
}
