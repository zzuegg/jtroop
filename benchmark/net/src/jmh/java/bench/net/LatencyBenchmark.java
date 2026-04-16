package bench.net;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Latency-focused benchmark using {@code Mode.AverageTime} +
 * {@code Mode.SampleTime} so JMH reports both mean and percentile distribution
 * (p50/p90/p99/p999) for a single send + blocking flush.
 *
 * <p>{@code Client.sendBlocking()} encodes on the caller thread, stages into
 * the EventLoop's MPSC queue, and blocks until the loop has written the bytes.
 * Measures end-to-end user → socket latency minus kernel TCP ack.
 *
 * <p>Signals:
 * <ul>
 *   <li>Mean ns/op — straightforward latency.</li>
 *   <li>p99 - p50 divergence &gt; 10x — scheduler or GC jitter; look at
 *       EventLoop wakeup cost and thread parking.</li>
 *   <li>If decodeOnly + encodeOnly sum &lt;&lt; latency mean, the difference is
 *       queuing + IO; if sum ≈ mean, codec is the bottleneck.</li>
 * </ul>
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class LatencyBenchmark {

    public record PositionUpdate(float x, float y, float z, float yaw) {}

    public interface GameService {
        void position(PositionUpdate p);
    }

    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate p, ConnectionId s) {}
    }

    record BenchConn(int v) {}

    private Server server;
    private Client client;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(new GameHandler(), BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        for (int i = 0; i < 10_000; i++) client.sendBlocking(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    /** Blocking send — measures the full user → wire write path latency. */
    @Benchmark
    public void positionUpdate_latency() {
        client.sendBlocking(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }
}
