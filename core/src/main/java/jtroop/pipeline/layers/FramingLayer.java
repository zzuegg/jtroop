package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;

public final class FramingLayer implements Layer {

    // Cached view buffer reused across decodeInbound calls to avoid per-frame
    // allocation of ByteBuffer.slice(). The view shares the backing array of the
    // last-seen wire buffer; if a different wire buffer arrives (different backing
    // array, e.g. another connection sharing this layer instance), a fresh
    // duplicate() is created and cached.
    //
    // Safety contract: the caller MUST fully consume the returned frame before
    // invoking decodeInbound again with the same wire buffer — Server/Client do
    // this in their tight while-loops. The view's position/limit are rewritten
    // on each call; the wire buffer's limit is never modified (only its position
    // advances to consume the frame bytes).
    private ByteBuffer view;

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        out.putInt(payload.remaining());
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int startPos = wire.position();
        // Peek length prefix without advancing wire position — keeps rewind
        // cheap when the payload is incomplete (no reset() needed).
        int length = wire.getInt(startPos);
        if (length < 0 || wire.remaining() - 4 < length) return null;

        int framePos = startPos + 4;
        int frameEnd = framePos + length;

        ByteBuffer v = view;
        // Rebind the cached view if we've never seen a wire buffer or the
        // backing storage changed (e.g. layer shared across connections).
        if (v == null || !sameBacking(v, wire)) {
            v = wire.duplicate();
            view = v;
        }
        // Order matters to avoid IllegalArgumentException: always widen the
        // limit to capacity first so the subsequent position() assignment is
        // guaranteed <= limit, then shrink limit to the frame end.
        v.limit(v.capacity());
        v.position(framePos);
        v.limit(frameEnd);

        // Advance the wire buffer past the consumed frame. The wire buffer's
        // limit is never touched, so callers that later compact()/clear() the
        // wire buffer see consistent state.
        wire.position(frameEnd);
        return v;
    }

    private static boolean sameBacking(ByteBuffer a, ByteBuffer b) {
        if (a.isDirect() != b.isDirect()) return false;
        if (a.hasArray() && b.hasArray()) {
            return a.array() == b.array() && a.arrayOffset() == b.arrayOffset();
        }
        // Both direct or otherwise — conservative: rebuild the view.
        return false;
    }
}
