package jtroop.pipeline;

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

    private final long connectionId;
    private volatile InetSocketAddress remoteAddress;
    private final long connectedAtNanos;
    private final Runnable closeAfterFlushAction;
    private final Runnable closeNowAction;

    // Byte counters. {@code volatile} because {@code handleRead} is serialised
    // on one loop but a broadcast/unicast write may come from a different
    // worker loop. Rate-limit filters read across threads.
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
    public void addBytesRead(long delta) { this.bytesRead += delta; }
    public void addBytesWritten(long delta) { this.bytesWritten += delta; }
    public void setRemoteAddress(InetSocketAddress addr) { this.remoteAddress = addr; }
}
