package net;

import net.client.Client;
import net.pipeline.layers.*;
import net.server.Server;
import net.service.*;
import net.session.ConnectionId;
import net.testing.Forwarder;
import net.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FullIntegrationTest {

    // --- Connection types ---
    record GameConn(int version, int caps) {
        static final int CHAT = 1, MOVE = 2;
        record Accepted(int version, int activeCaps) {}
    }
    record AuthConn(int version) {}

    // --- Messages ---
    record ChatMessage(String text, int room) {}
    record ChatAck(int room) {}
    record MoveCommand(float x, float y, float z) {}
    record PositionUpdate(float x, float y, float z, float yaw) {}
    record LoginRequest(String token) {}
    record LoginResponse(boolean ok, String session) {}
    record ServerPush(String data) {}

    // --- Service contracts ---
    interface ChatService {
        ChatAck send(ChatMessage msg);
    }
    interface MovementService {
        void command(MoveCommand cmd);
        @Datagram void position(PositionUpdate pos);
    }
    interface AuthService {
        LoginResponse login(LoginRequest req);
    }

    // --- Handlers ---
    @Handles(ChatService.class)
    static class ChatHandler {
        final CopyOnWriteArrayList<ChatMessage> messages = new CopyOnWriteArrayList<>();

        @OnMessage ChatAck send(ChatMessage msg, ConnectionId sender, Broadcast broadcast) {
            messages.add(msg);
            broadcast.send(new ServerPush("chat:" + msg.text()));
            return new ChatAck(msg.room());
        }

        @OnConnect void join(ConnectionId id) {}
        @OnDisconnect void leave(ConnectionId id) {}
    }

    @Handles(MovementService.class)
    static class MovementHandler {
        final CopyOnWriteArrayList<MoveCommand> commands = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<PositionUpdate> positions = new CopyOnWriteArrayList<>();
        CountDownLatch cmdLatch = new CountDownLatch(1);
        CountDownLatch posLatch = new CountDownLatch(1);

        @OnMessage void command(MoveCommand cmd, ConnectionId s) {
            commands.add(cmd); cmdLatch.countDown();
        }
        @OnMessage void position(PositionUpdate pos, ConnectionId s) {
            positions.add(pos); posLatch.countDown();
        }
    }

    @Handles(AuthService.class)
    static class AuthHandler {
        @OnMessage LoginResponse login(LoginRequest req, ConnectionId s) {
            return new LoginResponse("valid".equals(req.token()), "sess-1");
        }
    }

    // ─── Test: Full game stack with TCP+UDP, handshake, encryption, proxy, broadcast ───

    @Test
    void fullGame_tcpUdp_handshake_encryption_proxy_broadcast() throws Exception {
        var key = generateKey();
        var chatHandler = new ChatHandler();
        var moveHandler = new MovementHandler();

        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer(), new EncryptionLayer(key))
                .listen(GameConn.class, Transport.udp(0), new SequencingLayer())
                .onHandshake(GameConn.class, req -> {
                    if (req.version() >= 1) {
                        return new GameConn.Accepted(req.version(), req.caps() & (GameConn.CHAT | GameConn.MOVE));
                    }
                    return null;
                })
                .addService(chatHandler, GameConn.class)
                .addService(moveHandler, GameConn.class)
                .build();
        server.start();
        int tcpPort = server.port(GameConn.class);
        int udpPort = server.udpPort(GameConn.class);

        var pushReceived = new CopyOnWriteArrayList<ServerPush>();
        var pushLatch = new CountDownLatch(1);

        var client = Client.builder()
                .connect(new GameConn(2, GameConn.CHAT | GameConn.MOVE),
                        Transport.tcp("localhost", tcpPort), new FramingLayer(), new EncryptionLayer(key))
                .connect(GameConn.class, Transport.udp("localhost", udpPort), new SequencingLayer())
                .addService(ChatService.class, GameConn.class)
                .addService(MovementService.class, GameConn.class)
                .onMessage(ServerPush.class, msg -> { pushReceived.add(msg); pushLatch.countDown(); })
                .build();
        client.start();
        Thread.sleep(500);

        // 1. Verify handshake
        var accepted = client.handshakeResult(GameConn.Accepted.class);
        assertNotNull(accepted, "Handshake should succeed");
        assertEquals(2, accepted.version());

        // 2. Use typed proxy for chat (TCP, encrypted, request/response)
        ChatService chat = client.service(ChatService.class);
        var ack = chat.send(new ChatMessage("hello", 1));
        assertEquals(1, ack.room());

        // 3. Verify broadcast was received
        assertTrue(pushLatch.await(2, TimeUnit.SECONDS));
        assertEquals("chat:hello", pushReceived.getFirst().data());

        // 4. Fire-and-forget via TCP
        MovementService move = client.service(MovementService.class);
        move.command(new MoveCommand(1, 2, 3));
        assertTrue(moveHandler.cmdLatch.await(2, TimeUnit.SECONDS));

        // 5. @Datagram via UDP with sequencing
        move.position(new PositionUpdate(10, 20, 30, 0));
        assertTrue(moveHandler.posLatch.await(2, TimeUnit.SECONDS));
        assertEquals(10f, moveHandler.positions.getFirst().x());

        client.close();
        server.close();
    }

    // ─── Test: Multiple connection groups (game + auth servers) ───

    @Test
    void multiServer_gameAndAuth_separateConnections() throws Exception {
        var chatHandler = new ChatHandler();
        var authHandler = new AuthHandler();

        var gameServer = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(chatHandler, GameConn.class)
                .build();
        gameServer.start();

        var authServer = Server.builder()
                .listen(AuthConn.class, Transport.tcp(0), new FramingLayer())
                .addService(authHandler, AuthConn.class)
                .build();
        authServer.start();

        var client = Client.builder()
                .connect(GameConn.class, Transport.tcp("localhost", gameServer.port(GameConn.class)), new FramingLayer())
                .connect(AuthConn.class, Transport.tcp("localhost", authServer.port(AuthConn.class)), new FramingLayer())
                .addService(ChatService.class, GameConn.class)
                .addService(AuthService.class, AuthConn.class)
                .onMessage(ServerPush.class, _ -> {}) // ignore broadcasts
                .build();
        client.start();
        Thread.sleep(300);

        // Auth on auth server
        AuthService auth = client.service(AuthService.class);
        var loginResp = auth.login(new LoginRequest("valid"));
        assertTrue(loginResp.ok());

        // Chat on game server
        ChatService chat = client.service(ChatService.class);
        var chatAck = chat.send(new ChatMessage("hi", 5));
        assertEquals(5, chatAck.room());

        client.close();
        gameServer.close();
        authServer.close();
    }

    // ─── Test: UDP through forwarder with packet loss ───

    @Test
    void udpThroughForwarder_withPacketLoss() throws Exception {
        var moveHandler = new MovementHandler();
        moveHandler.posLatch = new CountDownLatch(50); // expect at least some

        var server = Server.builder()
                .listen(GameConn.class, Transport.udp(0))
                .addService(moveHandler, GameConn.class)
                .build();
        server.start();
        int udpPort = server.udpPort(GameConn.class);

        // UDP forwarder with 30% packet loss
        var forwarder = Forwarder.builder()
                .forward(Transport.udp(0), "localhost", udpPort)
                    .packetLoss(0.30)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(GameConn.class, Transport.udp("localhost", fwdPort))
                .addService(MovementService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Send 100 position updates through forwarder with 30% loss
        for (int i = 0; i < 100; i++) {
            client.send(new PositionUpdate(i, i, i, 0));
            Thread.sleep(5); // small gap between sends
        }

        Thread.sleep(500);
        int received = moveHandler.positions.size();
        // With 30% loss, expect roughly 50-90 to arrive
        assertTrue(received > 30, "Expected >30 received, got " + received);
        assertTrue(received < 95, "Expected <95 received (some loss), got " + received);

        client.close();
        forwarder.close();
        server.close();
    }

    // ─── Test: TCP through forwarder with latency + encryption ───

    @Test
    void tcpThroughForwarder_latencyAndEncryption() throws Exception {
        var key = generateKey();
        var chatHandler = new ChatHandler();

        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer(), new EncryptionLayer(key))
                .addService(chatHandler, GameConn.class)
                .build();
        server.start();
        int serverPort = server.port(GameConn.class);

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                    .latency(Duration.ofMillis(30), Duration.ofMillis(60))
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(GameConn.class, Transport.tcp("localhost", fwdPort),
                        new FramingLayer(), new EncryptionLayer(key))
                .addService(ChatService.class, GameConn.class)
                .onMessage(ServerPush.class, _ -> {})
                .build();
        client.start();
        Thread.sleep(500);

        long start = System.currentTimeMillis();
        var ack = client.request(new ChatMessage("encrypted through forwarder", 7), ChatAck.class);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(7, ack.room());
        assertTrue(elapsed >= 25, "Expected latency, got " + elapsed + "ms");

        client.close();
        forwarder.close();
        server.close();
    }

    // ─── Test: Concurrent clients through forwarder ───

    @Test
    void concurrentClients_throughForwarder() throws Exception {
        var chatHandler = new ChatHandler();

        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .addService(chatHandler, GameConn.class)
                .build();
        server.start();
        int serverPort = server.port(GameConn.class);

        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        int numClients = 5;
        var clients = new Client[numClients];
        for (int i = 0; i < numClients; i++) {
            clients[i] = Client.builder()
                    .connect(GameConn.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                    .addService(ChatService.class, GameConn.class)
                    .onMessage(ServerPush.class, _ -> {})
                    .build();
            clients[i].start();
        }
        Thread.sleep(500);

        var latch = new CountDownLatch(numClients);
        var errors = new AtomicInteger(0);
        for (int i = 0; i < numClients; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    ChatService chat = clients[idx].service(ChatService.class);
                    var result = chat.send(new ChatMessage("client-" + idx, idx));
                    assertEquals(idx, result.room());
                    latch.countDown();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(numClients, chatHandler.messages.size());

        for (var c : clients) c.close();
        forwarder.close();
        server.close();
    }

    private SecretKey generateKey() throws Exception {
        var gen = KeyGenerator.getInstance("AES");
        gen.init(128);
        return gen.generateKey();
    }
}
