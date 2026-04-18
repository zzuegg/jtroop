package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashSet;
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
        // Expand each IPv4 entry to also include its IPv4-mapped IPv6 form
        // (::ffff:a.b.c.d) and vice versa. A dual-stack Java NIO socket
        // reports an IPv4 peer that connects via the IPv6 listener with the
        // mapped form — without this canonicalisation, a legitimate peer
        // would be denied (false negative) and, symmetrically, a deny-list
        // entry in one form would be bypassable by connecting via the
        // other. Done once at construction; zero per-connection cost.
        var expanded = new HashSet<InetAddress>(allowed.size() * 2);
        for (InetAddress a : allowed) {
            expanded.add(a);
            InetAddress mate = mappedMate(a);
            if (mate != null) expanded.add(mate);
        }
        this.allowed = Set.copyOf(expanded);
    }

    /**
     * Returns the IPv4-mapped IPv6 form of an IPv4 address, or the IPv4
     * form of an IPv4-mapped IPv6 address, or {@code null} for any other
     * address family (genuine IPv6, site-local, etc.).
     */
    private static InetAddress mappedMate(InetAddress a) {
        try {
            if (a instanceof Inet4Address) {
                byte[] src = a.getAddress();
                byte[] m = new byte[16];
                m[10] = (byte) 0xFF;
                m[11] = (byte) 0xFF;
                m[12] = src[0];
                m[13] = src[1];
                m[14] = src[2];
                m[15] = src[3];
                return Inet6Address.getByAddress(null, m, 0);
            }
            if (a instanceof Inet6Address v6) {
                byte[] src = v6.getAddress();
                // IPv4-mapped IPv6 canonical form: ::ffff:a.b.c.d → bytes 0..9=0, 10..11=0xFF, 12..15=IPv4
                for (int i = 0; i < 10; i++) if (src[i] != 0) return null;
                if ((src[10] & 0xFF) != 0xFF || (src[11] & 0xFF) != 0xFF) return null;
                return InetAddress.getByAddress(new byte[]{src[12], src[13], src[14], src[15]});
            }
        } catch (UnknownHostException _) {
            // getByAddress only throws on wrong-length input — we build fixed-length arrays above.
        }
        return null;
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
