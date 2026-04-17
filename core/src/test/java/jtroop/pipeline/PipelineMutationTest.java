package jtroop.pipeline;

import jtroop.ConfigurationException;
import jtroop.generate.FusedPipelineGenerator;
import jtroop.pipeline.layers.CompressionLayer;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.pipeline.layers.HttpLayer;
import jtroop.pipeline.layers.WebSocketLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the immutable mutation helpers on {@link Pipeline} and confirms
 * they cooperate with the shape-indexed fused-class cache in
 * {@link FusedPipelineGenerator}.
 */
class PipelineMutationTest {

    @Test
    void addFirst_prependsLayer() {
        var base = new Pipeline(new FramingLayer());
        var with = base.addFirst(new CompressionLayer());
        assertEquals(1, base.size());
        assertEquals(2, with.size());
        assertEquals(CompressionLayer.class, with.layers()[0].getClass());
        assertEquals(FramingLayer.class, with.layers()[1].getClass());
    }

    @Test
    void addLast_appendsLayer() {
        var base = new Pipeline(new FramingLayer());
        var with = base.addLast(new CompressionLayer());
        assertEquals(2, with.size());
        assertEquals(FramingLayer.class, with.layers()[0].getClass());
        assertEquals(CompressionLayer.class, with.layers()[1].getClass());
    }

    @Test
    void remove_returnsUnchangedWhenLayerAbsent() {
        var base = new Pipeline(new FramingLayer());
        var removed = base.remove(CompressionLayer.class);
        assertSame(base, removed);
    }

    @Test
    void remove_deletesFirstMatchingLayer() {
        var base = new Pipeline(new CompressionLayer(), new FramingLayer());
        var removed = base.remove(CompressionLayer.class);
        assertEquals(1, removed.size());
        assertEquals(FramingLayer.class, removed.layers()[0].getClass());
    }

    @Test
    void replace_swapsInstanceInPlace() {
        var base = new Pipeline(new HttpLayer());
        var ws = new WebSocketLayer(WebSocketLayer.Role.SERVER);
        var upgraded = base.replace(HttpLayer.class, ws);
        assertEquals(1, upgraded.size());
        assertSame(ws, upgraded.layers()[0]);
    }

    @Test
    void replace_throwsWhenLayerAbsent() {
        var base = new Pipeline(new FramingLayer());
        assertThrows(ConfigurationException.class,
                () -> base.replace(HttpLayer.class, new HttpLayer()));
    }

    @Test
    void mutationReturnsFreshPipeline() {
        var base = new Pipeline(new FramingLayer());
        var addedFirst = base.addFirst(new CompressionLayer());
        var addedLast = base.addLast(new CompressionLayer());
        // Original must not be mutated.
        assertNotSame(base, addedFirst);
        assertNotSame(base, addedLast);
        assertEquals(1, base.size());
    }

    @Test
    void fusedClassCached_forIdenticalShape() {
        // Two pipelines with identical Layer class sequence must share the
        // same generated hidden class (shape-cache hit).
        var a = new Pipeline(new FramingLayer(), new CompressionLayer());
        var b = new Pipeline(new FramingLayer(), new CompressionLayer());
        assertSame(a.fused().getClass(), b.fused().getClass(),
                "shape cache should reuse class for identical layer-class tuple");
    }

    @Test
    void fusedClassRegenerated_afterShapeChange() {
        var http = new Pipeline(new HttpLayer());
        var withFraming = http.addLast(new FramingLayer());
        // Different shape → different fused class.
        assertNotSame(http.fused().getClass(), withFraming.fused().getClass());
    }

    @Test
    void fusedRoundtripCorrect_afterMutation() {
        // The generated hidden class must actually work end-to-end after a
        // mutation: build a pipeline, mutate it, round-trip a payload.
        var framing = new Pipeline(new FramingLayer());
        var withCompression = framing.addFirst(new CompressionLayer());

        var payload = ByteBuffer.allocate(64);
        for (int i = 0; i < 16; i++) payload.putInt(i);
        payload.flip();
        var original = new byte[payload.remaining()];
        payload.get(original);
        payload.flip();

        var wire = ByteBuffer.allocate(256);
        withCompression.fused().encodeOutbound(payload, wire);
        wire.flip();
        var decoded = withCompression.fused().decodeInbound(wire);
        assertNotNull(decoded);
        var round = new byte[decoded.remaining()];
        decoded.get(round);
        assertArrayEquals(original, round);
    }

    @Test
    void cacheMetrics_countHitsAndMisses() {
        long hitsBefore = FusedPipelineGenerator.cacheHits();
        long missesBefore = FusedPipelineGenerator.cacheMisses();
        // First pipeline — whether it's a miss depends on prior tests; this
        // test only validates that the counters move monotonically.
        new Pipeline(new FramingLayer(), new HttpLayer());
        new Pipeline(new FramingLayer(), new HttpLayer()); // second — guaranteed hit
        assertTrue(FusedPipelineGenerator.cacheHits() > hitsBefore,
                "second identical-shape pipeline must produce a cache hit");
        assertTrue(FusedPipelineGenerator.cacheMisses() >= missesBefore);
    }
}
