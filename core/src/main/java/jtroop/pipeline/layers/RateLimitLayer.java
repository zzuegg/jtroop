package jtroop.pipeline.layers;

import jtroop.ConfigurationException;
import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket-ish rate limit per connection, measured in bytes/second.
 *
 * <p>Uses {@code ctx.bytesRead()} and {@code ctx.connectedAtNanos()} for a
 * simple cumulative check: the effective rate is
 * {@code bytesRead / elapsedSeconds}. Crossing the configured threshold
 * triggers a one-shot {@code closeAfterFlush} so the client sees a clean
 * disconnect instead of a truncated response.
 *
 * <p>A short grace window ({@link #graceNanos}) skips the check for brand-new
 * connections so a single first-read burst doesn't get incorrectly flagged.
 *
 * <p><b>Per-instance state.</b> A small {@code ConcurrentHashMap<Long,
 * Boolean>} tracks which connections have already been closed; entries
 * are cleared automatically via {@link #onConnectionClose(long)} which
 * the server/client invoke on every close path.
 */
public final class RateLimitLayer implements Layer {

    private final long maxBytesPerSecond;
    private final long graceNanos;
    private final ConcurrentHashMap<Long, Boolean> closed = new ConcurrentHashMap<>();

    public RateLimitLayer(long maxBytesPerSecond) {
        this(maxBytesPerSecond, 100_000_000L); // 100ms grace window
    }

    public RateLimitLayer(long maxBytesPerSecond, long graceNanos) {
        if (maxBytesPerSecond <= 0) throw new ConfigurationException("maxBytesPerSecond must be positive");
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.graceNanos = graceNanos;
    }

    @Override
    public void encodeOutbound(Context ctx, ByteBuffer payload, ByteBuffer out) {
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(Context ctx, ByteBuffer wire) {
        long id = ctx.connectionId();
        if (closed.containsKey(id)) {
            // Already triggered — drop silently until the close actually fires.
            return null;
        }
        long bytes = ctx.bytesRead();
        long elapsed = System.nanoTime() - ctx.connectedAtNanos();
        if (elapsed < graceNanos) {
            return wire; // grace window — don't divide by a tiny number
        }
        // rate = bytes * NANOS_PER_SEC / elapsed ; compare to threshold
        // Re-arrange to avoid division by zero / overflow:
        //   bytes * 1e9 > maxBytesPerSecond * elapsed
        // Use double math for one comparison — cold branch, negligible cost.
        double ratePerSec = (double) bytes * 1_000_000_000.0 / (double) elapsed;
        if (ratePerSec > (double) maxBytesPerSecond) {
            if (closed.putIfAbsent(id, Boolean.TRUE) == null) {
                System.err.println("RateLimitLayer: connection " + id
                        + " exceeded " + maxBytesPerSecond + " B/s — closing");
                ctx.closeAfterFlush();
            }
            return null;
        }
        return wire;
    }

    /**
     * Auto-evicts the closed marker on connection close — invoked by
     * {@link jtroop.pipeline.Pipeline#onConnectionClose(long)} from the
     * server/client close paths.
     */
    @Override
    public void onConnectionClose(long connectionId) {
        closed.remove(connectionId);
    }

    /** Test hook. */
    public boolean hasClosed(long connectionId) {
        return closed.containsKey(connectionId);
    }
}
