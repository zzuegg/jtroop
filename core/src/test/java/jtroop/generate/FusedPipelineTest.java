package jtroop.generate;

import jtroop.pipeline.layers.FramingLayer;
import jtroop.pipeline.layers.CompressionLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FusedPipelineTest {

    @Test
    void fusedPipeline_singleLayer_roundtrip() {
        var fused = FusedPipelineGenerator.generate(new FramingLayer());

        var payload = ByteBuffer.allocate(64);
        payload.putInt(42);
        payload.flip();

        var wire = ByteBuffer.allocate(256);
        fused.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = fused.decodeInbound(wire);
        assertNotNull(decoded);
        assertEquals(42, decoded.getInt());
    }

    @Test
    void fusedPipeline_multipleLayerss_roundtrip() {
        var fused = FusedPipelineGenerator.generate(
                new CompressionLayer(), new FramingLayer());

        var payload = ByteBuffer.allocate(128);
        for (int i = 0; i < 20; i++) payload.putInt(i);
        payload.flip();
        var original = new byte[payload.remaining()];
        payload.get(original);
        payload.flip();

        var wire = ByteBuffer.allocate(512);
        fused.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = fused.decodeInbound(wire);
        assertNotNull(decoded);
        var result = new byte[decoded.remaining()];
        decoded.get(result);
        assertArrayEquals(original, result);
    }

    @Test
    void fusedPipeline_noLayers_passthrough() {
        var fused = FusedPipelineGenerator.generate();

        var payload = ByteBuffer.allocate(16);
        payload.putInt(99);
        payload.flip();

        var wire = ByteBuffer.allocate(64);
        fused.encodeOutbound(payload, wire);
        wire.flip();

        assertEquals(99, wire.getInt());
    }

    @Test
    void fusedPipeline_isConcreteType() {
        var fused = FusedPipelineGenerator.generate(new FramingLayer());
        // Should be a generated hidden class, not a Proxy
        assertFalse(java.lang.reflect.Proxy.isProxyClass(fused.getClass()));
        assertTrue(fused.getClass().isHidden());
    }
}
