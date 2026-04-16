package jtroop.generate;

import jtroop.codec.CodecRegistry;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.LayerContext;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FusedReceiverTest {

    public record TestPosition(float x, float y, float z, float yaw) {}
    public record TestChat(String text, int room) {}
    public record TestEcho(int seq) {}
    public record TestEchoAck(int seq) {}

    public interface TestService {
        void position(TestPosition pos);
        void chat(TestChat msg);
        TestEchoAck echo(TestEcho msg);
    }

    @Handles(TestService.class)
    public static class TestHandler {
        final AtomicInteger positionCount = new AtomicInteger();
        final AtomicReference<TestPosition> lastPosition = new AtomicReference<>();
        final AtomicInteger chatCount = new AtomicInteger();
        final AtomicReference<TestChat> lastChat = new AtomicReference<>();

        @OnMessage void position(TestPosition pos, ConnectionId sender) {
            positionCount.incrementAndGet();
            lastPosition.set(pos);
        }

        @OnMessage void chat(TestChat msg, ConnectionId sender) {
            chatCount.incrementAndGet();
            lastChat.set(msg);
        }

        // This is a returning handler — should NOT be fused
        @OnMessage TestEchoAck echo(TestEcho msg, ConnectionId sender) {
            return new TestEchoAck(msg.seq());
        }
    }

    @Test
    void fusedReceiver_positionUpdate_dispatchesCorrectly() {
        var codec = new CodecRegistry();
        codec.register(TestPosition.class);

        var pipeline = new Pipeline(new FramingLayer());
        var handler = new TestHandler();
        var bindings = FusedReceiverGenerator.collectBindings(handler, codec);

        // echo should be excluded (returning handler)
        assertTrue(bindings.stream().noneMatch(b ->
                b.messageType() == TestEcho.class));

        var layers = pipeline.layers();
        var receiver = FusedReceiverGenerator.generate(
                layers, bindings, Broadcast.NO_OP, Unicast.NO_OP);
        assertNotNull(receiver);

        // Encode a position update into wire format
        var encodeBuf = ByteBuffer.allocate(64);
        codec.encode(new TestPosition(1.0f, 2.0f, 3.0f, 0.5f), new WriteBuffer(encodeBuf));
        encodeBuf.flip();

        var wireBuf = ByteBuffer.allocate(256);
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();

        // Process it
        var sender = ConnectionId.of(1, 1);
        int result = receiver.processInbound(LayerContext.NOOP, wireBuf, sender);
        assertEquals(1, result);
        assertEquals(1, handler.positionCount.get());
        var pos = handler.lastPosition.get();
        assertNotNull(pos);
        assertEquals(1.0f, pos.x());
        assertEquals(2.0f, pos.y());
        assertEquals(3.0f, pos.z());
        assertEquals(0.5f, pos.yaw());
    }

    @Test
    void fusedReceiver_multipleFrames_drainAll() {
        var codec = new CodecRegistry();
        codec.register(TestPosition.class);

        var pipeline = new Pipeline(new FramingLayer());
        var handler = new TestHandler();
        var bindings = FusedReceiverGenerator.collectBindings(handler, codec);
        var layers = pipeline.layers();
        var receiver = FusedReceiverGenerator.generate(
                layers, bindings, Broadcast.NO_OP, Unicast.NO_OP);

        // Encode 5 frames
        var wireBuf = ByteBuffer.allocate(4096);
        for (int i = 0; i < 5; i++) {
            var encodeBuf = ByteBuffer.allocate(64);
            codec.encode(new TestPosition(i, i, i, i), new WriteBuffer(encodeBuf));
            encodeBuf.flip();

            var tmp = ByteBuffer.allocate(256);
            pipeline.encodeOutbound(encodeBuf, tmp);
            tmp.flip();
            wireBuf.put(tmp);
        }
        wireBuf.flip();

        var sender = ConnectionId.of(1, 1);
        int result = receiver.processInbound(LayerContext.NOOP, wireBuf, sender);
        assertEquals(5, result);
        assertEquals(5, handler.positionCount.get());
    }

    @Test
    void fusedReceiver_unknownTypeId_returnsMinusOne() {
        var codec = new CodecRegistry();
        codec.register(TestPosition.class);
        codec.register(TestEcho.class);

        var pipeline = new Pipeline(new FramingLayer());
        var handler = new TestHandler();
        var bindings = FusedReceiverGenerator.collectBindings(handler, codec);
        var layers = pipeline.layers();
        var receiver = FusedReceiverGenerator.generate(
                layers, bindings, Broadcast.NO_OP, Unicast.NO_OP);

        // Encode an EchoMsg — not handled by fused receiver
        var encodeBuf = ByteBuffer.allocate(64);
        codec.encode(new TestEcho(42), new WriteBuffer(encodeBuf));
        encodeBuf.flip();

        var wireBuf = ByteBuffer.allocate(256);
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();

        int wireStart = wireBuf.position();
        var sender = ConnectionId.of(1, 1);
        int result = receiver.processInbound(LayerContext.NOOP, wireBuf, sender);
        assertEquals(-1, result);
        // Wire buffer should be rewound to before the frame
        assertEquals(wireStart, wireBuf.position());
    }

    @Test
    void fusedReceiver_chatWithString_dispatchesCorrectly() {
        var codec = new CodecRegistry();
        codec.register(TestChat.class);

        var pipeline = new Pipeline(new FramingLayer());
        var handler = new TestHandler();
        var bindings = FusedReceiverGenerator.collectBindings(handler, codec);
        var layers = pipeline.layers();
        var receiver = FusedReceiverGenerator.generate(
                layers, bindings, Broadcast.NO_OP, Unicast.NO_OP);

        var encodeBuf = ByteBuffer.allocate(256);
        codec.encode(new TestChat("hello world", 42), new WriteBuffer(encodeBuf));
        encodeBuf.flip();

        var wireBuf = ByteBuffer.allocate(512);
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();

        var sender = ConnectionId.of(1, 1);
        int result = receiver.processInbound(LayerContext.NOOP, wireBuf, sender);
        assertEquals(1, result);
        assertEquals(1, handler.chatCount.get());
        var chat = handler.lastChat.get();
        assertNotNull(chat);
        assertEquals("hello world", chat.text());
        assertEquals(42, chat.room());
    }

    @Test
    void fusedReceiver_knownThenUnknown_handlesPartially() {
        var codec = new CodecRegistry();
        codec.register(TestPosition.class);
        codec.register(TestEcho.class);

        var pipeline = new Pipeline(new FramingLayer());
        var handler = new TestHandler();
        var bindings = FusedReceiverGenerator.collectBindings(handler, codec);
        var layers = pipeline.layers();
        var receiver = FusedReceiverGenerator.generate(
                layers, bindings, Broadcast.NO_OP, Unicast.NO_OP);

        // Encode: 2 position updates, then 1 echo (unknown to fused)
        var wireBuf = ByteBuffer.allocate(4096);
        for (int i = 0; i < 2; i++) {
            var encodeBuf = ByteBuffer.allocate(64);
            codec.encode(new TestPosition(i, i, i, i), new WriteBuffer(encodeBuf));
            encodeBuf.flip();
            var tmp = ByteBuffer.allocate(256);
            pipeline.encodeOutbound(encodeBuf, tmp);
            tmp.flip();
            wireBuf.put(tmp);
        }
        {
            var encodeBuf = ByteBuffer.allocate(64);
            codec.encode(new TestEcho(99), new WriteBuffer(encodeBuf));
            encodeBuf.flip();
            var tmp = ByteBuffer.allocate(256);
            pipeline.encodeOutbound(encodeBuf, tmp);
            tmp.flip();
            wireBuf.put(tmp);
        }
        wireBuf.flip();

        var sender = ConnectionId.of(1, 1);
        // Should dispatch 2 position updates, then return -1 for echo
        int result = receiver.processInbound(LayerContext.NOOP, wireBuf, sender);
        // The fused receiver processed frames until unknown type -> returns -1
        // (it dispatched 2 known frames, then hit unknown on 3rd)
        assertEquals(-1, result);
        assertEquals(2, handler.positionCount.get());
        // Wire buffer should be rewound to before the echo frame
        assertTrue(wireBuf.hasRemaining());
    }
}
