package jtroop.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Layer lifecycle callback plumbed through Pipeline.
 * onConnectionClose exists as a default no-op on Layer and is invoked once
 * per layer when a Pipeline is told of a connection close — cold path, used
 * by stateful layers to release per-connection memory without relying on
 * user-wired forget() calls.
 */
class LayerOnConnectionCloseTest {

    public static final class CountingLayer implements Layer {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong lastConnId = new AtomicLong(-1);
        @Override
        public void encodeOutbound(Context ctx, ByteBuffer payload, ByteBuffer out) {
            out.put(payload);
        }
        @Override
        public ByteBuffer decodeInbound(Context ctx, ByteBuffer wire) { return wire; }
        @Override
        public void onConnectionClose(long connectionId) {
            calls.incrementAndGet();
            lastConnId.set(connectionId);
        }
    }

    @Test
    void pipeline_invokesOnConnectionClose_onEveryLayer() {
        var l1 = new CountingLayer();
        var l2 = new CountingLayer();
        var l3 = new CountingLayer();
        var pipeline = new Pipeline(l1, l2, l3);

        pipeline.onConnectionClose(0xCAFEBABEL);

        assertEquals(1, l1.calls.get());
        assertEquals(1, l2.calls.get());
        assertEquals(1, l3.calls.get());
        assertEquals(0xCAFEBABEL, l1.lastConnId.get());
        assertEquals(0xCAFEBABEL, l2.lastConnId.get());
        assertEquals(0xCAFEBABEL, l3.lastConnId.get());
    }

    @Test
    void pipeline_multipleCloses_onDifferentConnections_counted() {
        var l = new CountingLayer();
        var pipeline = new Pipeline(l);

        pipeline.onConnectionClose(1L);
        pipeline.onConnectionClose(2L);
        pipeline.onConnectionClose(3L);

        assertEquals(3, l.calls.get());
        assertEquals(3L, l.lastConnId.get()); // last
    }

    @Test
    void layer_defaultOnConnectionClose_isNoOp() {
        // A layer that doesn't override onConnectionClose should not throw.
        Layer plain = new Layer() {
            @Override public void encodeOutbound(Context ctx, ByteBuffer p, ByteBuffer o) { o.put(p); }
            @Override public ByteBuffer decodeInbound(Context ctx, ByteBuffer w) { return w; }
        };
        assertDoesNotThrow(() -> plain.onConnectionClose(42L));
    }
}
