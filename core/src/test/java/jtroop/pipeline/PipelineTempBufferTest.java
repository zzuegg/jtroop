package jtroop.pipeline;

import jtroop.pipeline.layers.FramingLayer;
import org.junit.jupiter.api.Test;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipeline uses a per-layer 65536-byte temp buffer for outbound encoding.
 * These tests pin down what happens at that boundary so the limit isn't
 * accidentally relied on elsewhere (e.g. Server.readBuf is also 65536,
 * so the transport chain is consistent).
 */
class PipelineTempBufferTest {

    @Test
    void maxSizePayload_throughFramingLayer_fitsInTempBuffer() {
        // FramingLayer prepends 4 bytes of length. MAX_FRAME_LENGTH is sized
        // so that MAX + 4 == tempBuffer capacity (65536). Encoding a payload
        // of exactly MAX_FRAME_LENGTH must succeed — anything smaller is fine,
        // anything larger would overflow.
        var pipeline = new Pipeline(new FramingLayer());
        int max = FramingLayer.MAX_FRAME_LENGTH;

        var payload = ByteBuffer.allocate(max);
        for (int i = 0; i < max; i++) payload.put((byte) i);
        payload.flip();

        var wire = ByteBuffer.allocate(max + 4);
        pipeline.encodeOutbound(payload, wire);
        wire.flip();
        assertEquals(max + 4, wire.remaining());
    }

    @Test
    void oversizedPayload_overflowsTempBuffer() {
        // One byte more than MAX overflows the 65536-byte temp buffer because
        // FramingLayer writes 4 bytes of length + payload.remaining() bytes.
        // Document the limit: if a user wants larger frames they must
        // configure a bigger pipeline buffer (not currently supported).
        var pipeline = new Pipeline(new FramingLayer());
        int oversized = FramingLayer.MAX_FRAME_LENGTH + 1;

        var payload = ByteBuffer.allocate(oversized);
        for (int i = 0; i < oversized; i++) payload.put((byte) i);
        payload.flip();

        var wire = ByteBuffer.allocate(oversized + 4);
        assertThrows(BufferOverflowException.class,
                () -> pipeline.encodeOutbound(payload, wire));
    }

    @Test
    void pipelineEncode_isReentrantSafeOnSameThread() {
        // Pipeline temp buffers are instance-local. Two sequential encodes on
        // the same pipeline must both succeed without leftover state between
        // calls. (cleared at the top of encodeOutbound — verifying that.)
        var pipeline = new Pipeline(new FramingLayer());
        var out1 = ByteBuffer.allocate(128);
        var out2 = ByteBuffer.allocate(128);

        var payload1 = ByteBuffer.allocate(16);
        payload1.putInt(111);
        payload1.flip();
        pipeline.encodeOutbound(payload1, out1);
        out1.flip();

        var payload2 = ByteBuffer.allocate(16);
        payload2.putInt(222);
        payload2.flip();
        pipeline.encodeOutbound(payload2, out2);
        out2.flip();

        assertEquals(4, out1.getInt()); // length prefix
        assertEquals(111, out1.getInt());
        assertEquals(4, out2.getInt());
        assertEquals(222, out2.getInt());
    }
}
