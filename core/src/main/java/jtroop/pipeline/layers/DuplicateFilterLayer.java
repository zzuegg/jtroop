package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;

public final class DuplicateFilterLayer implements Layer {

    private final LinkedHashSet<Integer> seen;
    private final int capacity;

    public DuplicateFilterLayer(int capacity) {
        this.capacity = capacity;
        this.seen = new LinkedHashSet<>();
    }

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        // Pass through — duplicate filter is receive-side only
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        wire.mark();
        int seq = wire.getInt();
        wire.reset();

        if (seen.contains(seq)) {
            // Duplicate — drop
            wire.position(wire.limit());
            return null;
        }

        seen.add(seq);
        if (seen.size() > capacity) {
            // Evict oldest
            var iter = seen.iterator();
            iter.next();
            iter.remove();
        }

        // Return entire buffer (including seq — downstream layers may need it)
        var result = wire.slice(wire.position(), wire.remaining());
        wire.position(wire.limit());
        return result;
    }
}
