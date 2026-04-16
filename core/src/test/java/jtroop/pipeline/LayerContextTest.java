package jtroop.pipeline;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic wiring test for {@link Layer.Context}: a pipeline of three recording
 * layers must see the same Context instance on every call.
 */
class LayerContextTest {

    /** Records which Context instance (identity-wise) each call observed.
     *  Public-nested-public so the FusedPipelineGenerator's hidden class can
     *  reference it. */
    public static final class RecordingLayer implements Layer {
        public final List<Context> decodeCalls = new ArrayList<>();
        public final List<Context> encodeCalls = new ArrayList<>();

        @Override
        public void encodeOutbound(Context ctx, ByteBuffer payload, ByteBuffer out) {
            encodeCalls.add(ctx);
            out.put(payload);
        }

        @Override
        public ByteBuffer decodeInbound(Context ctx, ByteBuffer wire) {
            decodeCalls.add(ctx);
            return wire;
        }
    }

    @Test
    void contextThreadedThroughEveryLayer_decode_and_encode() {
        var a = new RecordingLayer();
        var b = new RecordingLayer();
        var c = new RecordingLayer();
        var pipeline = new Pipeline(a, b, c);

        var ctx = new LayerContext(
                0x00000001_00000042L,
                new InetSocketAddress("127.0.0.1", 9000),
                System.nanoTime(),
                () -> {},
                () -> {});

        var payload = ByteBuffer.allocate(16);
        payload.putInt(123);
        payload.flip();
        var wire = ByteBuffer.allocate(64);
        pipeline.encodeOutbound(ctx, payload, wire);
        wire.flip();

        var frame = pipeline.decodeInbound(ctx, wire);
        assertNotNull(frame);

        // Every layer saw the exact instance — no new Context allocation along the chain.
        assertEquals(1, a.encodeCalls.size()); assertSame(ctx, a.encodeCalls.get(0));
        assertEquals(1, b.encodeCalls.size()); assertSame(ctx, b.encodeCalls.get(0));
        assertEquals(1, c.encodeCalls.size()); assertSame(ctx, c.encodeCalls.get(0));
        assertEquals(1, a.decodeCalls.size()); assertSame(ctx, a.decodeCalls.get(0));
        assertEquals(1, b.decodeCalls.size()); assertSame(ctx, b.decodeCalls.get(0));
        assertEquals(1, c.decodeCalls.size()); assertSame(ctx, c.decodeCalls.get(0));
    }

    @Test
    void fusedPipelineAlsoPassesContextThrough() {
        var a = new RecordingLayer();
        var b = new RecordingLayer();
        var pipeline = new Pipeline(a, b);

        var ctx = new LayerContext(
                0x00000001_00000007L,
                new InetSocketAddress("10.0.0.1", 5555),
                0L,
                () -> {},
                () -> {});

        var payload = ByteBuffer.allocate(16);
        payload.putInt(7);
        payload.flip();
        var wire = ByteBuffer.allocate(64);

        pipeline.fused().encodeOutbound(ctx, payload, wire);
        wire.flip();
        var frame = pipeline.fused().decodeInbound(ctx, wire);
        assertNotNull(frame);

        for (var seen : a.encodeCalls) assertSame(ctx, seen);
        for (var seen : b.encodeCalls) assertSame(ctx, seen);
        for (var seen : a.decodeCalls) assertSame(ctx, seen);
        for (var seen : b.decodeCalls) assertSame(ctx, seen);
    }

    @Test
    void closeCallbacksInvokedOnDemand() {
        var fired = new boolean[2];
        var ctx = new LayerContext(
                0L, null, 0L,
                () -> fired[0] = true,
                () -> fired[1] = true);
        ctx.closeAfterFlush();
        ctx.closeNow();
        assertTrue(fired[0], "closeAfterFlush should fire its action");
        assertTrue(fired[1], "closeNow should fire its action");
    }

    @Test
    void byteCountersAccumulate() {
        var ctx = new LayerContext(0L, null, 0L, () -> {}, () -> {});
        ctx.addBytesRead(100);
        ctx.addBytesRead(50);
        ctx.addBytesWritten(25);
        assertEquals(150, ctx.bytesRead());
        assertEquals(25, ctx.bytesWritten());
    }
}
