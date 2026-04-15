package net;

import net.client.Client;
import net.pipeline.layers.CompressionLayer;
import net.pipeline.layers.EncryptionLayer;
import net.pipeline.layers.FramingLayer;
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
import java.util.EnumSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class EndToEndTest {

    // --- Connection types ---
    enum GameCapability { CHAT, MOVEMENT }

    record GameConnection(int version, EnumSet<GameCapability> capabilities) {
        record Accepted(int negotiatedVersion) {}
    }

    record AuthConnection(int version) {
        record Accepted(int version) {}
    }

    // --- Messages ---
    record ChatMessage(String text, int room) {}
    record ChatAck(int room) {}
    record MoveCommand(float x, float y, float z) {}
    record LoginRequest(String token) {}
    record LoginResponse(boolean success, String sessionId) {}

    // --- Service contracts ---
    interface ChatService {
        ChatAck send(ChatMessage msg);
    }

    interface MovementService {
        void move(MoveCommand cmd);
    }

    interface AuthService {
        LoginResponse login(LoginRequest req);
    }

    // --- Handlers ---
    @Handles(ChatService.class)
    static class ChatHandler {
        final CopyOnWriteArrayList<ChatMessage> messages = new CopyOnWriteArrayList<>();

        @OnMessage
        ChatAck send(ChatMessage msg, ConnectionId sender) {
            messages.add(msg);
            return new ChatAck(msg.room());
        }
    }

    @Handles(MovementService.class)
    static class MovementHandler {
        final CopyOnWriteArrayList<MoveCommand> moves = new CopyOnWriteArrayList<>();
        CountDownLatch moveLatch = new CountDownLatch(1);

        @OnMessage
        void move(MoveCommand cmd, ConnectionId sender) {
            moves.add(cmd);
            moveLatch.countDown();
        }
    }

    @Handles(AuthService.class)
    static class AuthHandler {
        @OnMessage
        LoginResponse login(LoginRequest req, ConnectionId sender) {
            if ("valid-token".equals(req.token())) {
                return new LoginResponse(true, "session-123");
            }
            return new LoginResponse(false, null);
        }
    }

    @Test
    void fullStack_multiService_withFramingAndCompression() throws Exception {
        var chatHandler = new ChatHandler();
        var moveHandler = new MovementHandler();

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0),
                        new FramingLayer(), new CompressionLayer())
                .addService(chatHandler, GameConnection.class)
                .addService(moveHandler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port),
                        new FramingLayer(), new CompressionLayer())
                .addService(ChatService.class, GameConnection.class)
                .addService(MovementService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Request/response
        var ack = client.request(new ChatMessage("hello world", 1), ChatAck.class);
        assertEquals(1, ack.room());
        assertEquals(1, chatHandler.messages.size());
        assertEquals("hello world", chatHandler.messages.getFirst().text());

        // Fire-and-forget
        client.send(new MoveCommand(1.0f, 2.0f, 3.0f));
        assertTrue(moveHandler.moveLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, moveHandler.moves.size());

        client.close();
        server.close();
    }

    @Test
    void fullStack_withEncryption() throws Exception {
        var key = generateKey();
        var chatHandler = new ChatHandler();

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0),
                        new FramingLayer(), new EncryptionLayer(key))
                .addService(chatHandler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port),
                        new FramingLayer(), new EncryptionLayer(key))
                .addService(ChatService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        var ack = client.request(new ChatMessage("encrypted msg", 5), ChatAck.class);
        assertEquals(5, ack.room());
        assertEquals("encrypted msg", chatHandler.messages.getFirst().text());

        client.close();
        server.close();
    }

    @Test
    void fullStack_multipleConnectionGroups() throws Exception {
        var chatHandler = new ChatHandler();
        var authHandler = new AuthHandler();

        // Game server
        var gameServer = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(chatHandler, GameConnection.class)
                .build();
        gameServer.start();

        // Auth server (separate)
        var authServer = Server.builder()
                .listen(AuthConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(authHandler, AuthConnection.class)
                .build();
        authServer.start();

        // Client connects to both
        var client = Client.builder()
                .connect(GameConnection.class,
                        Transport.tcp("localhost", gameServer.port(GameConnection.class)),
                        new FramingLayer())
                .connect(AuthConnection.class,
                        Transport.tcp("localhost", authServer.port(AuthConnection.class)),
                        new FramingLayer())
                .addService(ChatService.class, GameConnection.class)
                .addService(AuthService.class, AuthConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Login on auth server
        var loginResp = client.request(new LoginRequest("valid-token"), LoginResponse.class);
        assertTrue(loginResp.success());
        assertEquals("session-123", loginResp.sessionId());

        // Chat on game server
        var ack = client.request(new ChatMessage("hello", 1), ChatAck.class);
        assertEquals(1, ack.room());

        client.close();
        gameServer.close();
        authServer.close();
    }

    @Test
    void fullStack_throughForwarder_withLatency() throws Exception {
        var chatHandler = new ChatHandler();

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(chatHandler, GameConnection.class)
                .build();
        server.start();
        int serverPort = server.port(GameConnection.class);

        // Forwarder with 50-100ms latency
        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                    .latency(Duration.ofMillis(50), Duration.ofMillis(100))
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", fwdPort), new FramingLayer())
                .addService(ChatService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(500);

        long start = System.currentTimeMillis();
        var ack = client.request(new ChatMessage("through forwarder", 3), ChatAck.class);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, ack.room());
        assertEquals("through forwarder", chatHandler.messages.getFirst().text());
        // At least some latency added (forwarder adds to both directions)
        assertTrue(elapsed >= 40, "Expected latency, got " + elapsed + "ms");

        client.close();
        forwarder.close();
        server.close();
    }

    @Test
    void fullStack_multipleClients_concurrent() throws Exception {
        var chatHandler = new ChatHandler();

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0), new FramingLayer())
                .addService(chatHandler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        int numClients = 5;
        var clients = new Client[numClients];
        for (int i = 0; i < numClients; i++) {
            clients[i] = Client.builder()
                    .connect(GameConnection.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(ChatService.class, GameConnection.class)
                    .build();
            clients[i].start();
        }
        Thread.sleep(500);

        // Each client sends a message
        var latch = new CountDownLatch(numClients);
        for (int i = 0; i < numClients; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    var ack = clients[idx].request(new ChatMessage("from-" + idx, idx), ChatAck.class);
                    assertEquals(idx, ack.room());
                    latch.countDown();
                } catch (Exception e) { e.printStackTrace(); }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(numClients, chatHandler.messages.size());

        for (var c : clients) c.close();
        server.close();
    }

    @Test
    void fullStack_framingCompressionEncryption_allLayers() throws Exception {
        var key = generateKey();
        var chatHandler = new ChatHandler();

        var server = Server.builder()
                .listen(GameConnection.class, Transport.tcp(0),
                        new FramingLayer(), new CompressionLayer(), new EncryptionLayer(key))
                .addService(chatHandler, GameConnection.class)
                .build();
        server.start();
        int port = server.port(GameConnection.class);

        var client = Client.builder()
                .connect(GameConnection.class, Transport.tcp("localhost", port),
                        new FramingLayer(), new CompressionLayer(), new EncryptionLayer(key))
                .addService(ChatService.class, GameConnection.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Send a larger message to exercise compression
        var longText = "x".repeat(1000);
        var ack = client.request(new ChatMessage(longText, 99), ChatAck.class);
        assertEquals(99, ack.room());
        assertEquals(longText, chatHandler.messages.getFirst().text());

        client.close();
        server.close();
    }

    private SecretKey generateKey() throws Exception {
        var gen = KeyGenerator.getInstance("AES");
        gen.init(128);
        return gen.generateKey();
    }
}
