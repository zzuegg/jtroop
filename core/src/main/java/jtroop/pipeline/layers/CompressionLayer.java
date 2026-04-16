package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib compression layer.
 *
 * <p><b>Zero-allocation hot path.</b> The {@link Deflater}, {@link Inflater}
 * and scratch {@code byte[]} buffers are held as instance fields and reused
 * across every call. {@link #encodeOutbound} allocates nothing once the
 * scratch arrays have grown to the working set size; {@link #decodeInbound}
 * returns a cached {@link ByteBuffer} view over the output scratch.
 *
 * <p><b>Threading contract.</b> Same as {@link FramingLayer}: the caller
 * MUST fully consume the decoded {@link ByteBuffer} before invoking
 * {@code decodeInbound} again on this instance, and concurrent access must
 * be externally serialised (Server/Client event loops already serialise the
 * shared pipeline). The layer is effectively stateless semantically — no
 * data is carried between calls — but it owns mutable scratch state.
 *
 * <p>The {@link Deflater}/{@link Inflater} are native-resource backed;
 * {@link #close()} ends them. A layer typically lives for the lifetime of
 * the server/client, so leaking one instance is acceptable; tests that spin
 * up many instances should close.
 */
public final class CompressionLayer implements Layer, AutoCloseable {

    private final Deflater deflater;
    private final Inflater inflater;

    // Input side scratch arrays. Grow on demand; never shrink. After warmup
    // these stabilise to the hot-path working set, giving zero allocation.
    private byte[] inputScratch = new byte[512];
    private byte[] compressedScratch = new byte[512];

    // Output side scratch array plus a cached ByteBuffer view. The view is
    // rebuilt only when the array has to grow; on the steady-state hot path
    // both remain referentially identical across calls.
    private byte[] outputScratch = new byte[512];
    private ByteBuffer outputView = ByteBuffer.wrap(outputScratch);

    public CompressionLayer() {
        this(Deflater.DEFAULT_COMPRESSION);
    }

    /**
     * @param level deflate level (0..9, or {@link Deflater#DEFAULT_COMPRESSION}).
     *              Lower values trade ratio for throughput; on small, noisy
     *              payloads a level of 1 often wins.
     */
    public CompressionLayer(int level) {
        this.deflater = new Deflater(level);
        this.inflater = new Inflater();
    }

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        int len = payload.remaining();
        byte[] in = inputScratch;
        if (in.length < len) {
            in = new byte[Math.max(len, in.length * 2)];
            inputScratch = in;
        }
        payload.get(in, 0, len);

        // Worst case with zlib on incompressible data: len + ceil(len/16384)*5 + 6.
        // +64 is a safe margin for any reasonable single-shot frame; the while
        // loop below handles the pathological case.
        byte[] comp = compressedScratch;
        int needed = len + (len >> 8) + 64;
        if (comp.length < needed) {
            comp = new byte[Math.max(needed, comp.length * 2)];
            compressedScratch = comp;
        }

        deflater.reset();
        deflater.setInput(in, 0, len);
        deflater.finish();
        int compressedLen = deflater.deflate(comp, 0, comp.length);
        while (!deflater.finished()) {
            byte[] bigger = new byte[comp.length * 2];
            System.arraycopy(comp, 0, bigger, 0, compressedLen);
            comp = bigger;
            compressedScratch = comp;
            compressedLen += deflater.deflate(comp, compressedLen, comp.length - compressedLen);
        }

        out.putInt(len); // original size for inflate
        out.put(comp, 0, compressedLen);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int originalSize = wire.getInt();
        int compLen = wire.remaining();

        byte[] comp = compressedScratch;
        if (comp.length < compLen) {
            comp = new byte[Math.max(compLen, comp.length * 2)];
            compressedScratch = comp;
        }
        wire.get(comp, 0, compLen);

        byte[] outArr = outputScratch;
        if (outArr.length < originalSize) {
            outArr = new byte[Math.max(originalSize, outArr.length * 2)];
            outputScratch = outArr;
            outputView = ByteBuffer.wrap(outArr);
        }

        inflater.reset();
        inflater.setInput(comp, 0, compLen);
        int produced;
        try {
            produced = inflater.inflate(outArr, 0, originalSize);
        } catch (DataFormatException e) {
            throw new RuntimeException("Decompression failed", e);
        }

        ByteBuffer view = outputView;
        view.limit(view.capacity());
        view.position(0);
        view.limit(produced);
        return view;
    }

    @Override
    public void close() {
        deflater.end();
        inflater.end();
    }
}
