package jtroop.service;

import jtroop.codec.CodecRegistry;
import jtroop.session.ConnectionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleDispatchTest {

    record PingMsg(int v) {}
    record PongMsg(int v) {}

    interface PingService {
        PongMsg ping(PingMsg m);
    }

    @Handles(PingService.class)
    static class LifecycleHandler {
        final List<ConnectionId> connected = new ArrayList<>();
        final List<ConnectionId> disconnected = new ArrayList<>();

        @OnMessage
        PongMsg ping(PingMsg m, ConnectionId sender) { return new PongMsg(m.v()); }

        @OnConnect
        void onConnect(ConnectionId id) { connected.add(id); }

        @OnDisconnect
        void onDisconnect(ConnectionId id) { disconnected.add(id); }
    }

    @Test
    void dispatchConnect_callsOnConnectHandler() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var handler = new LifecycleHandler();
        registry.register(handler);

        var id = ConnectionId.of(0, 1);
        registry.dispatchConnect(id);

        assertEquals(1, handler.connected.size());
        assertEquals(id, handler.connected.getFirst());
    }

    @Test
    void dispatchDisconnect_callsOnDisconnectHandler() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var handler = new LifecycleHandler();
        registry.register(handler);

        var id = ConnectionId.of(3, 2);
        registry.dispatchDisconnect(id);

        assertEquals(1, handler.disconnected.size());
        assertEquals(id, handler.disconnected.getFirst());
    }

    @Test
    void multipleHandlers_allReceiveLifecycleEvents() {
        record MoveMsg(float x) {}
        interface MoveService { void move(MoveMsg m); }

        @Handles(MoveService.class)
        class MoveHandler {
            final List<ConnectionId> connected = new ArrayList<>();
            @OnConnect void onConnect(ConnectionId id) { connected.add(id); }
            @OnMessage void move(MoveMsg m, ConnectionId s) {}
        }

        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var h1 = new LifecycleHandler();
        var h2 = new MoveHandler();
        registry.register(h1);
        registry.register(h2);

        var id = ConnectionId.of(0, 1);
        registry.dispatchConnect(id);

        assertEquals(1, h1.connected.size());
        assertEquals(1, h2.connected.size());
    }
}
