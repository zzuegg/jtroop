package net.server;

import net.client.Client;
import net.pipeline.layers.FramingLayer;
import net.service.*;
import net.session.ConnectionId;
import net.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HandshakeTest {

    // Use int bitmask for capabilities (codec supports primitives)
    record GameConn(int version, int capabilityMask) {
        static final int CHAT = 1, VOICE = 2, VIDEO = 4;
        record Accepted(int negotiatedVersion, int activeMask) {}
    }

    record PingMsg(int v) {}
    record PongMsg(int v) {}

    interface PingSvc { PongMsg ping(PingMsg m); }

    @Handles(PingSvc.class)
    static class PingHandler {
        @OnMessage PongMsg ping(PingMsg m, ConnectionId s) { return new PongMsg(m.v()); }
    }

    @Test
    void handshake_serverAcceptsCompatibleClient() throws Exception {
        int serverCaps = GameConn.CHAT | GameConn.VOICE;
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(GameConn.class, req -> {
                    if (req.version() >= 1) {
                        int common = req.capabilityMask() & serverCaps;
                        return new GameConn.Accepted(req.version(), common);
                    }
                    return null;
                })
                .addService(PingHandler.class, GameConn.class)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        int allCaps = GameConn.CHAT | GameConn.VOICE | GameConn.VIDEO;
        var client = Client.builder()
                .connect(new GameConn(2, allCaps),
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(PingSvc.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        assertTrue(client.isConnected(GameConn.class));

        var accepted = client.handshakeResult(GameConn.Accepted.class);
        assertNotNull(accepted);
        assertEquals(2, accepted.negotiatedVersion());
        assertEquals(GameConn.CHAT | GameConn.VOICE, accepted.activeMask()); // no VIDEO

        // Should still work for normal messages
        var pong = client.request(new PingMsg(42), PongMsg.class);
        assertEquals(42, pong.v());

        client.close();
        server.close();
    }

    @Test
    void handshake_serverRejectsIncompatibleClient() throws Exception {
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .onHandshake(GameConn.class, req -> {
                    if (req.version() >= 2) {
                        return new GameConn.Accepted(req.version(), req.capabilityMask());
                    }
                    return null; // reject old versions
                })
                .addService(PingHandler.class, GameConn.class)
                .build();
        server.start();
        int port = server.port(GameConn.class);

        var client = Client.builder()
                .connect(new GameConn(1, GameConn.CHAT), // version 1 = too old
                        Transport.tcp("localhost", port), new FramingLayer())
                .addService(PingSvc.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        // Client should detect rejection
        assertFalse(client.isConnected(GameConn.class));

        client.close();
        server.close();
    }
}
