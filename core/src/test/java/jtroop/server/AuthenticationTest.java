package jtroop.server;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authentication is built on top of the handshake mechanism.
 * Client sends credentials in the connection record, server validates in onHandshake.
 * Rejected clients never reach any service handler.
 */
@Timeout(10)
class AuthenticationTest {

    // Connection record carries auth credentials
    public record AuthConn(String username, String token, int version) {
        public record Accepted(int negotiatedVersion, String sessionId, int permissions) {}
    }

    record SecureMsg(String data) {}

    interface SecureService { void send(SecureMsg msg); }

    @Handles(SecureService.class)
    static class SecureHandler {
        final CopyOnWriteArrayList<SecureMsg> received = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<ConnectionId> authenticatedConnections = new CopyOnWriteArrayList<>();
        CountDownLatch receivedLatch = new CountDownLatch(1);

        @OnMessage void send(SecureMsg msg, ConnectionId sender) {
            received.add(msg);
            receivedLatch.countDown();
        }

        @OnConnect void onAuth(ConnectionId id) {
            // This only fires AFTER successful handshake
            authenticatedConnections.add(id);
        }
    }

    // Example auth backend
    static class AuthBackend {
        private static final java.util.Map<String, String> USERS = java.util.Map.of(
                "alice", "token-abc",
                "bob", "token-xyz"
        );
        private final java.util.concurrent.atomic.AtomicInteger sessionCounter = new java.util.concurrent.atomic.AtomicInteger();

        boolean validate(String username, String token) {
            return token.equals(USERS.get(username));
        }

        String newSession(String username) {
            return "sess-" + username + "-" + sessionCounter.incrementAndGet();
        }
    }

    @Test
    void validCredentials_acceptsConnection() throws Exception {
        var handler = new SecureHandler();
        var auth = new AuthBackend();

        var server = Server.builder()
                .listen(AuthConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(AuthConn.class, req -> {
                    if (!auth.validate(req.username(), req.token())) {
                        return null; // reject
                    }
                    return new AuthConn.Accepted(req.version(), auth.newSession(req.username()), 0x0F);
                })
                .addService(handler, AuthConn.class)
                .build();
        server.start();
        int port = server.port(AuthConn.class);

        var client = Client.builder()
                .connect(new AuthConn("alice", "token-abc", 1),
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(SecureService.class, AuthConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        assertTrue(client.isConnected(AuthConn.class));

        var accepted = client.handshakeResult(AuthConn.Accepted.class);
        assertNotNull(accepted);
        assertTrue(accepted.sessionId().startsWith("sess-alice-"));
        assertEquals(0x0F, accepted.permissions());

        // Handler should have fired @OnConnect for the authenticated user
        assertEquals(1, handler.authenticatedConnections.size());

        // Send a message after auth
        client.send(new SecureMsg("hello after auth"));
        assertTrue(handler.receivedLatch.await(2, TimeUnit.SECONDS));
        assertEquals("hello after auth", handler.received.getFirst().data());

        client.close();
        server.close();
    }

    @Test
    void invalidToken_rejectsConnection() throws Exception {
        var handler = new SecureHandler();
        var auth = new AuthBackend();

        var server = Server.builder()
                .listen(AuthConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(AuthConn.class, req -> {
                    if (!auth.validate(req.username(), req.token())) return null;
                    return new AuthConn.Accepted(req.version(), auth.newSession(req.username()), 0x0F);
                })
                .addService(handler, AuthConn.class)
                .build();
        server.start();
        int port = server.port(AuthConn.class);

        var client = Client.builder()
                .connect(new AuthConn("alice", "WRONG-TOKEN", 1),
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(SecureService.class, AuthConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        assertFalse(client.isConnected(AuthConn.class));

        // Handler's @OnConnect should never fire for rejected clients
        assertEquals(0, handler.authenticatedConnections.size());

        client.close();
        server.close();
    }

    @Test
    void unknownUser_rejectsConnection() throws Exception {
        var handler = new SecureHandler();
        var auth = new AuthBackend();

        var server = Server.builder()
                .listen(AuthConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(AuthConn.class, req -> {
                    if (!auth.validate(req.username(), req.token())) return null;
                    return new AuthConn.Accepted(req.version(), auth.newSession(req.username()), 0x0F);
                })
                .addService(handler, AuthConn.class)
                .build();
        server.start();
        int port = server.port(AuthConn.class);

        var client = Client.builder()
                .connect(new AuthConn("eve", "any-token", 1),
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(SecureService.class, AuthConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        assertFalse(client.isConnected(AuthConn.class));
        client.close();
        server.close();
    }

    @Test
    void multipleUsers_sessionsTrackedIndependently() throws Exception {
        var handler = new SecureHandler();
        handler.receivedLatch = new CountDownLatch(2);
        var auth = new AuthBackend();
        var sessions = new ConcurrentHashMap<ConnectionId, String>();

        var server = Server.builder()
                .listen(AuthConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(AuthConn.class, req -> {
                    if (!auth.validate(req.username(), req.token())) return null;
                    var sid = auth.newSession(req.username());
                    return new AuthConn.Accepted(req.version(), sid, 0x0F);
                })
                .addService(handler, AuthConn.class)
                .build();
        server.start();
        int port = server.port(AuthConn.class);

        var alice = Client.builder()
                .connect(new AuthConn("alice", "token-abc", 1),
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(SecureService.class, AuthConn.class)
                .build();
        alice.start();

        var bob = Client.builder()
                .connect(new AuthConn("bob", "token-xyz", 1),
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(SecureService.class, AuthConn.class)
                .build();
        bob.start();
        Thread.sleep(500);

        assertTrue(alice.isConnected(AuthConn.class));
        assertTrue(bob.isConnected(AuthConn.class));

        var aliceSession = alice.handshakeResult(AuthConn.Accepted.class);
        var bobSession = bob.handshakeResult(AuthConn.Accepted.class);

        assertTrue(aliceSession.sessionId().startsWith("sess-alice-"));
        assertTrue(bobSession.sessionId().startsWith("sess-bob-"));
        assertNotEquals(aliceSession.sessionId(), bobSession.sessionId());

        assertEquals(2, handler.authenticatedConnections.size());

        alice.close();
        bob.close();
        server.close();
    }
}
