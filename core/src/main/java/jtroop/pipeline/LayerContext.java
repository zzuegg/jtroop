package jtroop.pipeline;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;

/**
 * Default per-connection {@link Layer.Context} implementation. One instance
 * per accepted connection; reused for the life of the connection.
 *
 * <p>Close callbacks are wired at construction time — Server/Client supply
 * {@link Runnable}s that enqueue the actual close onto the owning event loop.
 * Keeping the Context itself as a tiny POJO (one packed long, one ref, two
 * volatile longs, two runnable refs) means the extra parameter threaded
 * through the pipeline is a single reload of a stack slot.
 */
public final class LayerContext implements Layer.Context {

    // VarHandles for atomic add on byte counters. volatile read-modify-write
    // (+=) is not atomic — concurrent addBytesWritten from broadcast fan-out
    // on different worker loops would lose updates. VarHandle.getAndAdd is
    // a single atomic RMW instruction (lock xadd on x86).
    private static final VarHandle BYTES_READ;
    private static final VarHandle BYTES_WRITTEN;
    static {
        try {
            var lookup = MethodHandles.lookup();
            BYTES_READ = lookup.findVarHandle(LayerContext.class, "bytesRead", long.class);
            BYTES_WRITTEN = lookup.findVarHandle(LayerContext.class, "bytesWritten", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long connectionId;
    private volatile InetSocketAddress remoteAddress;
    private final long connectedAtNanos;
    private final Runnable closeAfterFlushAction;
    private final Runnable closeNowAction;

    // Byte counters. Accessed via VarHandle for atomic read-modify-write.
    // Reads serialised on one loop, writes may come from broadcast fan-out
    // on different worker loops.
    private volatile long bytesRead;
    private volatile long bytesWritten;

    public LayerContext(long connectionId,
                        InetSocketAddress remoteAddress,
                        long connectedAtNanos,
                        Runnable closeAfterFlushAction,
                        Runnable closeNowAction) {
        this.connectionId = connectionId;
        this.remoteAddress = remoteAddress;
        this.connectedAtNanos = connectedAtNanos;
        this.closeAfterFlushAction = closeAfterFlushAction;
        this.closeNowAction = closeNowAction;
    }

    /** Shared singleton for tests / non-connection paths (handshake replies,
     *  UDP unconnected packets when no peer is tracked). Close callbacks are
     *  no-ops. Byte counters stay at zero. */
    public static final Layer.Context NOOP = new Layer.Context() {
        @Override public long connectionId() { return 0L; }
        @Override public InetSocketAddress remoteAddress() { return null; }
        @Override public long bytesRead() { return 0L; }
        @Override public long bytesWritten() { return 0L; }
        @Override public long connectedAtNanos() { return 0L; }
        @Override public void closeAfterFlush() { /* no-op */ }
        @Override public void closeNow() { /* no-op */ }
    };

    @Override public long connectionId() { return connectionId; }
    @Override public InetSocketAddress remoteAddress() { return remoteAddress; }
    @Override public long bytesRead() { return bytesRead; }
    @Override public long bytesWritten() { return bytesWritten; }
    @Override public long connectedAtNanos() { return connectedAtNanos; }

    @Override public void closeAfterFlush() { closeAfterFlushAction.run(); }
    @Override public void closeNow() { closeNowAction.run(); }

    // Package-private mutators used by Server/Client I/O paths.
    // Atomic RMW via VarHandle — safe when broadcast fan-out calls
    // addBytesWritten from multiple worker loops concurrently.
    public void addBytesRead(long delta) { BYTES_READ.getAndAdd(this, delta); }
    public void addBytesWritten(long delta) { BYTES_WRITTEN.getAndAdd(this, delta); }
    public void setRemoteAddress(InetSocketAddress addr) { this.remoteAddress = addr; }
}
