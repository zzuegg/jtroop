package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Early filter: rejects connections whose remote address is not in the
 * allow-set. Typically placed FIRST in the pipeline so malformed bytes from
 * blocked peers never reach the framing / codec layers.
 *
 * <p>The layer checks {@code ctx.remoteAddress()} once per connection and
 * caches the decision in a {@code ConcurrentHashMap<Long, Boolean>} keyed by
 * the packed connection id. Subsequent {@code decodeInbound} calls hit the
 * cache and return immediately — the branch is predicted taken.
 *
 * <p>On a denial: calls {@code ctx.closeNow()} and returns {@code null} so
 * the pipeline short-circuits; no upstream layer observes the bytes.
 *
 * <p><b>Per-instance state.</b> The decision cache is shared across all
 * connections that see this layer instance, so one instance can safely be
 * reused for every accepted connection. Entries are evicted automatically
 * via {@link #onConnectionClose(long)} which the {@code Server}/{@code
 * Client} invoke on every close path — no user wiring required.
 */
public final class AllowListLayer implements Layer {

    private final Set<InetAddress> allowed;
    private final ConcurrentHashMap<Long, Boolean> decisions = new ConcurrentHashMap<>();

    public AllowListLayer(Set<InetAddress> allowed) {
        java.util.Objects.requireNonNull(allowed, "parameter 'allowed' must not be null");
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public void encodeOutbound(Context ctx, ByteBuffer payload, ByteBuffer out) {
        // Pass-through — allow-list is receive-side.
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(Context ctx, ByteBuffer wire) {
        long id = ctx.connectionId();
        var cached = decisions.get(id);
        if (cached == null) {
            boolean ok = isAllowed(ctx.remoteAddress());
            decisions.put(id, ok);
            cached = ok;
            if (!ok) {
                ctx.closeNow();
                return null;
            }
        } else if (!cached.booleanValue()) {
            // Already denied — drop any late bytes.
            ctx.closeNow();
            return null;
        }
        return wire;
    }

    private boolean isAllowed(InetSocketAddress peer) {
        if (peer == null) return false;
        return allowed.contains(peer.getAddress());
    }

    /**
     * Auto-evicts the cached decision when the connection closes. Invoked
     * once per connection by {@link jtroop.pipeline.Pipeline#onConnectionClose(long)}
     * from the server/client close paths.
     */
    @Override
    public void onConnectionClose(long connectionId) {
        decisions.remove(connectionId);
    }

    /** Exposed for tests: how many decisions are currently cached. */
    public int cachedCount() { return decisions.size(); }
}
