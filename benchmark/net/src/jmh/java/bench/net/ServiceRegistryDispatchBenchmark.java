package bench.net;

import jtroop.codec.CodecRegistry;
import jtroop.service.Broadcast;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.service.ServiceRegistry;
import jtroop.service.Unicast;
import jtroop.session.ConnectionId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Isolates {@link ServiceRegistry#dispatch(Record, ConnectionId)} to measure
 * per-call allocation and latency. Targets the common shapes:
 *
 * <ul>
 *   <li>fire-and-forget (Record, ConnectionId) -> void</li>
 *   <li>with Broadcast injection (Record, ConnectionId, Broadcast) -> void</li>
 *   <li>request/response (Record, ConnectionId) -> Record</li>
 * </ul>
 *
 * Previous implementation used {@code MethodHandle.invokeWithArguments(Object[])}
 * which allocated an {@code Object[]} on every call. Current implementation uses
 * a generated hidden-class {@code HandlerInvoker} with a fixed signature.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class ServiceRegistryDispatchBenchmark {

    public record PosUpdate(float x, float y, float z, float yaw) {}
    public record ChatReq(int room) {}
    public record ChatResp(int ack) {}
    public record Echo(int v) {}

    public interface GameSvc {
        void position(PosUpdate p);
        ChatResp chat(ChatReq r);
        void echo(Echo e);
    }

    @Handles(GameSvc.class)
    public static class VoidHandler {
        @OnMessage void position(PosUpdate p, ConnectionId sender) {
            // consume
        }
    }

    @Handles(GameSvc.class)
    public static class ReturningHandler {
        @OnMessage ChatResp chat(ChatReq r, ConnectionId sender) {
            return new ChatResp(r.room());
        }
    }

    @Handles(GameSvc.class)
    public static class BroadcastHandler {
        @OnMessage void echo(Echo e, ConnectionId sender, Broadcast broadcast) {
            // consume
        }
    }

    private ServiceRegistry voidRegistry;
    private ServiceRegistry returningRegistry;
    private ServiceRegistry broadcastRegistry;

    private final PosUpdate pos = new PosUpdate(1f, 2f, 3f, 0.5f);
    private final ChatReq chat = new ChatReq(42);
    private final Echo echo = new Echo(7);
    private final ConnectionId sender = ConnectionId.of(1, 1);

    @Setup(Level.Trial)
    public void setup() {
        voidRegistry = new ServiceRegistry(new CodecRegistry());
        voidRegistry.register(new VoidHandler());

        returningRegistry = new ServiceRegistry(new CodecRegistry());
        returningRegistry.register(new ReturningHandler());

        broadcastRegistry = new ServiceRegistry(new CodecRegistry());
        broadcastRegistry.register(new BroadcastHandler());
        broadcastRegistry.setBroadcast(msg -> { /* no-op */ });
        broadcastRegistry.setUnicast((id, msg) -> { /* no-op */ });

        // Pre-warm branch profiles so C2 sees hot paths during measurement.
        for (int i = 0; i < 20_000; i++) {
            voidRegistry.dispatch(pos, sender);
            returningRegistry.dispatch(chat, sender);
            broadcastRegistry.dispatch(echo, sender);
        }
    }

    /** (Record, ConnectionId) -> void. Simplest shape. */
    @Benchmark
    public void dispatch_void(Blackhole bh) {
        bh.consume(voidRegistry.dispatch(pos, sender));
    }

    /** (Record, ConnectionId) -> Record. Handler allocates a response record. */
    @Benchmark
    public void dispatch_returning(Blackhole bh) {
        bh.consume(returningRegistry.dispatch(chat, sender));
    }

    /** (Record, ConnectionId, Broadcast) -> void. Extra injectable. */
    @Benchmark
    public void dispatch_broadcast(Blackhole bh) {
        bh.consume(broadcastRegistry.dispatch(echo, sender));
    }
}
