package jtroop.pipeline.layers;

import jtroop.pipeline.LayerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RateLimitLayerTest {

    /** Burst that exceeds the configured rate triggers closeAfterFlush. */
    @Test
    void exceedingRate_triggersCloseAfterFlush() throws Exception {
        // Cap at 100 B/s.
        var layer = new RateLimitLayer(100L, /* grace */ 0L);

        boolean[] flushed = {false};
        boolean[] closedNow = {false};
        // Pretend we connected 1 second ago.
        long connectedAt = System.nanoTime() - 1_000_000_000L;
        var ctx = new LayerContext(
                0x00000001_00000001L,
                new InetSocketAddress("127.0.0.1", 9000),
                connectedAt,
                () -> flushed[0] = true,
                () -> closedNow[0] = true);

        // Feed 10_000 bytes "received". Rate = 10_000 B/s, way above 100 B/s.
        ctx.addBytesRead(10_000);
        var wire = ByteBuffer.allocate(8);
        wire.putInt(1);
        wire.flip();
        var out = layer.decodeInbound(ctx, wire);
        // Key assertion: over-limit peer must be shut down and frame dropped.
        assertNull(out, "over-limit frame must be dropped");
        assertTrue(flushed[0], "rate-limit overrun must call closeAfterFlush");
        assertTrue(layer.hasClosed(ctx.connectionId()));
    }

    @Test
    void belowRate_passesThrough() {
        var layer = new RateLimitLayer(1_000_000L, /* grace */ 0L);
        // Simulated 1s connection with 100 bytes read → rate = 100 B/s << 1MB/s.
        long connectedAt = System.nanoTime() - 1_000_000_000L;
        var ctx = new LayerContext(
                2L, null, connectedAt,
                () -> fail("should not close"),
                () -> fail("should not close"));
        ctx.addBytesRead(100);

        var wire = ByteBuffer.allocate(8);
        wire.putInt(7);
        wire.flip();
        var out = layer.decodeInbound(ctx, wire);
        assertSame(wire, out, "under-limit peer passes through unchanged");
        assertFalse(layer.hasClosed(ctx.connectionId()));
    }

    @Test
    void graceWindow_skipsCheck() {
        var layer = new RateLimitLayer(1L, /* grace */ 10_000_000_000L);
        // connectedAt is now → elapsed < grace, check skipped.
        var ctx = new LayerContext(
                3L, null, System.nanoTime(),
                () -> fail("grace window must not close"),
                () -> fail("grace window must not close"));
        ctx.addBytesRead(1_000_000);
        var wire = ByteBuffer.allocate(8);
        wire.putInt(7);
        wire.flip();
        assertSame(wire, layer.decodeInbound(ctx, wire));
    }

    @Test
    void repeatedOverage_onlyClosesOnce() {
        var layer = new RateLimitLayer(100L, /* grace */ 0L);
        int[] closeCount = {0};
        long connectedAt = System.nanoTime() - 1_000_000_000L;
        var ctx = new LayerContext(
                4L, null, connectedAt,
                () -> closeCount[0]++,
                () -> closeCount[0]++);
        ctx.addBytesRead(1_000_000);
        var wire = ByteBuffer.allocate(8);
        wire.putInt(1);
        wire.flip();
        layer.decodeInbound(ctx, wire);
        wire.rewind();
        layer.decodeInbound(ctx, wire);
        wire.rewind();
        layer.decodeInbound(ctx, wire);
        // A single closeAfterFlush emits; subsequent reads are silently dropped.
        assertEquals(1, closeCount[0]);
    }
}
