package jtroop.pipeline.layers;

import jtroop.ProtocolException;
import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib compression layer with small-payload bypass.
 *
 * <p><b>Zero-allocation hot path.</b> The {@link Deflater}, {@link Inflater}
 * and scratch {@code byte[]} buffers are held as instance fields and reused
 * across every call. {@link #encodeOutbound} allocates nothing once the
 * scratch arrays have grown to the working set size; {@link #decodeInbound}
 * returns a cached {@link ByteBuffer} view over the output scratch.
 *
 * <p><b>Small-payload bypass.</b> For payloads smaller than
 * {@link #minCompressSize()} bytes (default 64), zlib is skipped entirely
 * and the payload is written raw. A 1-byte flag prefix distinguishes
 * compressed ({@code 0x01}) from raw ({@code 0x00}) frames. This avoids
 * the CPU cost of deflate/inflate on tiny payloads where zlib's 11-byte
 * header overhead often <em>increases</em> wire size. The crossover point
 * where zlib becomes beneficial depends on data entropy but is typically
 * around 64-128 bytes for game-style payloads (low-entropy floats).
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

    /** Wire flag: payload is raw (not compressed). */
    private static final byte FLAG_RAW = 0x00;
    /** Wire flag: payload is zlib-compressed. */
    private static final byte FLAG_COMPRESSED = 0x01;

    /** Default minimum payload size before compression is attempted. */
    private static final int DEFAULT_MIN_COMPRESS_SIZE = 64;

    /**
     * Default upper bound on the {@code originalSize} a peer may announce in
     * a compressed frame. 16 MiB is generous for any game/IoT/RPC payload
     * while putting a firm ceiling on the worst-case decompression
     * allocation a malicious peer can force. Configurable via the 3-arg
     * constructor.
     */
    public static final int DEFAULT_MAX_UNCOMPRESSED = 16 * 1024 * 1024;

    private final Deflater deflater;
    private final Inflater inflater;
    private final int minCompressSize;
    private final int maxUncompressedSize;

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
        this(level, DEFAULT_MIN_COMPRESS_SIZE);
    }

    /**
     * @param level           deflate level (0..9, or {@link Deflater#DEFAULT_COMPRESSION}).
     * @param minCompressSize payloads smaller than this (bytes) are passed through
     *                        raw without zlib. Set to 0 to always compress.
     */
    public CompressionLayer(int level, int minCompressSize) {
        this(level, minCompressSize, DEFAULT_MAX_UNCOMPRESSED);
    }

    /**
     * @param level               deflate level (0..9, or {@link Deflater#DEFAULT_COMPRESSION}).
     * @param minCompressSize     payloads smaller than this (bytes) are passed through raw.
     * @param maxUncompressedSize upper bound on the {@code originalSize} a peer may
     *                            announce in a compressed frame. Frames claiming a
     *                            larger uncompressed payload are rejected with
     *                            {@link ProtocolException} BEFORE any allocation —
     *                            prevents decompression-bomb DoS. Must be &gt; 0.
     */
    public CompressionLayer(int level, int minCompressSize, int maxUncompressedSize) {
        if (maxUncompressedSize <= 0) {
            throw new jtroop.ConfigurationException("maxUncompressedSize must be positive");
        }
        this.deflater = new Deflater(level);
        this.inflater = new Inflater();
        this.minCompressSize = minCompressSize;
        this.maxUncompressedSize = maxUncompressedSize;
    }

    /** Returns the minimum payload size (bytes) that triggers compression. */
    public int minCompressSize() {
        return minCompressSize;
    }

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        int len = payload.remaining();
        if (len > maxUncompressedSize) {
            // Refuse to produce a frame whose own decoder would reject it.
            throw new ProtocolException(
                    "payload size " + len + " exceeds configured maxUncompressedSize " +
                    maxUncompressedSize);
        }

        // Small-payload bypass: skip zlib entirely, write raw with flag.
        if (len < minCompressSize) {
            out.put(FLAG_RAW);
            out.put(payload);
            return;
        }

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

        out.put(FLAG_COMPRESSED);
        out.putInt(len); // original size for inflate
        out.put(comp, 0, compressedLen);
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        if (wire.remaining() < 1) return null;
        byte flag = wire.get();

        // Raw (uncompressed) frame: copy into outputScratch for consistent
        // return type, reusing the cached view.
        if (flag == FLAG_RAW) {
            int rawLen = wire.remaining();
            byte[] outArr = outputScratch;
            if (outArr.length < rawLen) {
                outArr = new byte[Math.max(rawLen, outArr.length * 2)];
                outputScratch = outArr;
                outputView = ByteBuffer.wrap(outArr);
            }
            wire.get(outArr, 0, rawLen);
            ByteBuffer view = outputView;
            view.clear();
            view.limit(rawLen);
            return view;
        }

        if (wire.remaining() < 4) return null;
        int originalSize = wire.getInt();
        // Decompression-bomb defense: reject announced sizes that are
        // negative or above the configured ceiling BEFORE allocating the
        // output buffer. A malicious peer could otherwise force ~2 GB
        // allocations with a handful of tiny compressed payloads.
        if (originalSize < 0 || originalSize > maxUncompressedSize) {
            throw new ProtocolException(
                    "compressed frame declares originalSize=" + originalSize +
                    " (max " + maxUncompressedSize + ")");
        }
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
            throw new ProtocolException("Decompression failed", e);
        }

        ByteBuffer view = outputView;
        view.clear();
        view.limit(produced);
        return view;
    }

    @Override
    public void close() {
        deflater.end();
        inflater.end();
    }
}
