package bench.net;

import jtroop.session.ConnectionId;
import jtroop.session.SessionStore;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@link SessionStore#forEachActive} iteration cost.
 *
 * <p>Run with the {@code gc} profiler (already default in this module's
 * build.gradle.kts) to read {@code gc.alloc.rate.norm} (B/op). Target per
 * CLAUDE.md: 0 B/op once warm, i.e. the {@link ConnectionId} record built
 * inside the loop body must be scalar-replaced by C2.
 *
 * <p>Three densities are profiled so the effect of sparse-vs-dense scan cost
 * on a 4k slot store is visible.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class SessionStoreBenchmark {

    private static final int CAPACITY = 4096;

    @Param({"64", "1024", "4096"})
    public int activeCount;

    private SessionStore store;
    private long checksum;
    // Consumer captured once as a field to avoid per-benchmark-invocation
    // lambda capture noise — we want to profile the iteration itself, not
    // the capture site.
    private java.util.function.Consumer<ConnectionId> sinkConsumer;
    private Blackhole bhRef;

    @Setup(Level.Trial)
    public void setup() {
        store = new SessionStore(CAPACITY);
        // Allocate `activeCount` slots, evenly spaced across the capacity,
        // so scan work is realistic (not a dense prefix).
        int stride = Math.max(1, CAPACITY / activeCount);
        var allocated = new ConnectionId[activeCount];
        int filled = 0;
        for (int i = 0; filled < activeCount && i < CAPACITY; i++) {
            var id = store.allocate();
            allocated[filled++] = id;
        }
        // Release slots that fall outside the stride pattern so the active
        // set is sparse across the capacity range.
        for (int i = 0; i < filled; i++) {
            if (i % stride != 0 && filled - i > activeCount - (filled / stride)) {
                store.release(allocated[i]);
            }
        }
        // Pre-warm branch profiles (CLAUDE.md rule #8).
        sinkConsumer = id -> checksum ^= id.id();
        for (int i = 0; i < 10_000; i++) {
            store.forEachActive(sinkConsumer);
        }
    }

    /** Iteration cost via field-captured consumer (no per-call capture). */
    @Benchmark
    public void forEachActive_fieldConsumer() {
        store.forEachActive(sinkConsumer);
    }

    /**
     * Iteration cost via a fresh lambda capturing a local Blackhole.
     * This is the realistic user pattern; EA must scalar-replace both the
     * {@link ConnectionId} record and (ideally) the lambda itself.
     */
    @Benchmark
    public void forEachActive_freshLambda(Blackhole bh) {
        store.forEachActive(id -> bh.consume(id.id()));
    }

    /**
     * Baseline: raw index scan that reproduces the internal loop without
     * allocating any ConnectionId at all. Divergence between this and
     * {@code forEachActive_*} indicates EA is not scalar-replacing the record.
     */
    @Benchmark
    public long rawIndexScan() {
        long acc = 0;
        // Probe through public API only; intentionally does not peek at
        // internal arrays so this reflects the isActive call cost.
        for (int i = 0; i < CAPACITY; i++) {
            var probe = ConnectionId.of(i, 1);
            if (store.isActive(probe)) {
                acc ^= probe.id();
            }
        }
        return acc;
    }
}
