package jtroop.pipeline.layers;

import jtroop.ProtocolException;
import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;

public final class FramingLayer implements Layer {

    /**
     * Upper bound on a single frame's payload length. Must stay in sync with
     * the read buffer capacity used by Server/Client (65536 bytes). 65532 =
     * 65536 (readBuf capacity) - 4 (length prefix itself).
     *
     * <p>Without a cap, a corrupt or hostile length prefix would stall the
     * read loop forever: compact() makes no progress waiting for bytes that
     * will never arrive. Throwing is caught by Server.handleRead which closes
     * the misbehaving connection.
     */
    public static final int MAX_FRAME_LENGTH = 65532;

    /**
     * Thrown when the peer sends a negative or oversized length prefix.
     */
    public static final class FramingException extends ProtocolException {
        public FramingException(String msg) { super(msg); }
    }

    // Cached view buffer reused across decodeInbound calls to avoid per-frame
    // allocation from ByteBuffer.slice(). Shares the backing array of the
    // last-seen wire buffer; rebuilt via duplicate() if a different wire
    // buffer arrives (e.g. layer shared across connections).
    //
    // Safety contract: the caller MUST fully consume the returned frame
    // before invoking decodeInbound again with the same wire buffer —
    // Server/Client do this in their tight while-loops. The view's
    // position/limit are rewritten on each call; the wire buffer's limit
    // is never modified (only its position advances past the consumed frame).
    private ByteBuffer view;

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        out.putInt(payload.remaining());
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int startPos = wire.position();
        // Peek length prefix without advancing position — no reset() needed on partial.
        int length = wire.getInt(startPos);
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new FramingException("Invalid frame length: " + length);
        }
        if (wire.remaining() - 4 < length) return null;

        int framePos = startPos + 4;
        int frameEnd = framePos + length;

        ByteBuffer v = view;
        if (v == null || !sameBacking(v, wire)) {
            v = wire.duplicate();
            view = v;
        }
        // Widen limit to capacity before moving position so the assignment
        // is guaranteed <= limit; then shrink limit to the frame end.
        v.limit(v.capacity());
        v.position(framePos);
        v.limit(frameEnd);

        wire.position(frameEnd);
        return v;
    }

    private static boolean sameBacking(ByteBuffer a, ByteBuffer b) {
        if (a.isDirect() != b.isDirect()) return false;
        if (a.hasArray() && b.hasArray()) {
            return a.array() == b.array() && a.arrayOffset() == b.arrayOffset();
        }
        return false;
    }
}
