package jtroop.service;

import jtroop.codec.CodecRegistry;
import jtroop.session.ConnectionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ServiceRegistryTest {

    // --- Test message types ---
    record ChatMessage(String text, int room) {}
    record HistoryRequest(int room) {}
    record ChatHistory(String data) {}
    record PositionUpdate(float x, float y, float z) {}
    record TeleportCommand(long targetId, float x, float y, float z) {}
    record TypingIndicator(boolean typing) {}

    // --- Test service contracts ---
    interface ChatService {
        void send(ChatMessage msg);
        ChatHistory getHistory(HistoryRequest req);
        @Datagram void typing(TypingIndicator t);
    }

    interface MovementService {
        void teleport(TeleportCommand cmd);
        @Datagram void position(PositionUpdate pos);
    }

    // --- Test handler ---
    @Handles(ChatService.class)
    static class ChatHandler {
        final List<ChatMessage> received = new ArrayList<>();
        final List<HistoryRequest> historyRequests = new ArrayList<>();

        @OnMessage
        void send(ChatMessage msg, ConnectionId sender) {
            received.add(msg);
        }

        @OnMessage
        ChatHistory getHistory(HistoryRequest req, ConnectionId sender) {
            historyRequests.add(req);
            return new ChatHistory("history for room " + req.room());
        }

        @OnMessage
        void typing(TypingIndicator t, ConnectionId sender) {
            // just receive
        }
    }

    @Handles(MovementService.class)
    static class MovementHandler {
        final List<TeleportCommand> teleports = new ArrayList<>();
        final List<PositionUpdate> positions = new ArrayList<>();

        @OnMessage
        void teleport(TeleportCommand cmd, ConnectionId sender) {
            teleports.add(cmd);
        }

        @OnMessage
        void position(PositionUpdate pos, ConnectionId sender) {
            positions.add(pos);
        }
    }

    @Test
    void register_discoversOnMessageMethods() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        registry.register(ChatHandler.class);

        // Should have registered all message types from ChatService
        assertDoesNotThrow(() -> codec.typeId(ChatMessage.class));
        assertDoesNotThrow(() -> codec.typeId(HistoryRequest.class));
        assertDoesNotThrow(() -> codec.typeId(TypingIndicator.class));
    }

    @Test
    void register_discoversReturnTypes() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        registry.register(ChatHandler.class);

        // ChatHistory is a response type, should also be registered
        assertDoesNotThrow(() -> codec.typeId(ChatHistory.class));
    }

    @Test
    void dispatch_fireAndForget() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var handler = new ChatHandler();
        registry.register(handler);

        var sender = ConnectionId.of(0, 1);
        registry.dispatch(new ChatMessage("hello", 1), sender);

        assertEquals(1, handler.received.size());
        assertEquals("hello", handler.received.getFirst().text());
    }

    @Test
    void dispatch_requestResponse() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var handler = new ChatHandler();
        registry.register(handler);

        var sender = ConnectionId.of(0, 1);
        var result = registry.dispatch(new HistoryRequest(5), sender);

        assertNotNull(result);
        assertInstanceOf(ChatHistory.class, result);
        assertEquals("history for room 5", ((ChatHistory) result).data());
    }

    @Test
    void dispatch_multipleHandlers() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var chatHandler = new ChatHandler();
        var moveHandler = new MovementHandler();
        registry.register(chatHandler);
        registry.register(moveHandler);

        var sender = ConnectionId.of(0, 1);
        registry.dispatch(new ChatMessage("hi", 1), sender);
        registry.dispatch(new TeleportCommand(1L, 0f, 0f, 0f), sender);

        assertEquals(1, chatHandler.received.size());
        assertEquals(1, moveHandler.teleports.size());
    }

    @Test
    void datagram_annotation_presentOnInterfaceMethod() {
        // @Datagram is now resolved at proxy generation time, not in the
        // registry. Verify it's still readable via reflection on the
        // service interface.
        boolean found = false;
        for (var m : ChatService.class.getDeclaredMethods()) {
            if (m.getName().equals("typing") && m.isAnnotationPresent(Datagram.class)) {
                found = true;
            }
        }
        assertTrue(found, "@Datagram should be present on ChatService.typing");
    }

    @Test
    void serviceInterface_resolvedFromHandler() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        registry.register(new ChatHandler());

        assertEquals(ChatService.class, registry.serviceInterface(ChatHandler.class));
    }

    // --- Handler inheritance: @OnMessage on a superclass must be discovered ---

    interface PingService { void ping(ChatMessage msg); }

    static abstract class PingBaseHandler {
        final List<ChatMessage> receivedByBase = new ArrayList<>();
        @OnMessage
        void ping(ChatMessage msg, ConnectionId sender) {
            receivedByBase.add(msg);
        }
    }

    @Handles(PingService.class)
    static class PingSubHandler extends PingBaseHandler { }

    @Test
    void register_findsOnMessageDeclaredInSuperclass() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        var handler = new PingSubHandler();
        registry.register(handler);

        // Pre-fix: getDeclaredMethods on PingSubHandler yields nothing (no
        // methods declared there), so the @OnMessage in PingBaseHandler is
        // silently skipped and dispatch would throw "No handler for message
        // type". Post-fix: the inherited annotation is picked up.
        registry.dispatch(new ChatMessage("hi", 0), ConnectionId.of(0, 1));
        assertEquals(1, handler.receivedByBase.size());
        assertEquals("hi", handler.receivedByBase.get(0).text());
    }

    @Test
    void messageTypes_listedForServiceInterface() {
        var codec = new CodecRegistry();
        var registry = new ServiceRegistry(codec);
        registry.register(new ChatHandler());

        var types = registry.messageTypes(ChatService.class);
        assertTrue(types.contains(ChatMessage.class));
        assertTrue(types.contains(HistoryRequest.class));
        assertTrue(types.contains(TypingIndicator.class));
    }
}
