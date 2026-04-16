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
 * 100 concurrent clients all connected to the same server, each sending a
 * position update per op. The benchmark thread cycles which client sends so
 * the server side has to demultiplex across many SelectionKeys.
 *
 * <p>Signals:
 * <ul>
 *   <li>ops/ms / 100 ≈ per-connection effective rate. Compare to the single
 *       client {@code positionUpdate} rate — drops exceeding ~2x mean the
 *       server is contended on a shared data structure or selector.</li>
 *   <li>B/op &gt; 0 with constant 100 clients points at per-send allocation in
 *       server-side state keeping (ConcurrentHashMap lookups, etc.).</li>
 *   <li>Watch for gc.count &gt; 0: fan-in selectors under load can surface
 *       allocation bugs that a single client never triggers.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class ManyClientsBenchmark {

    public record PositionUpdate(float x, float y, float z, float yaw) {}

    public interface GameService {
        void position(PositionUpdate p);
    }

    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate p, ConnectionId s) {}
    }

    record BenchConn(int v) {}

    private static final int CLIENT_COUNT = 100;

    private Server server;
    private Client[] clients;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(new GameHandler(), BenchConn.class)
                .eventLoops(4)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        clients = new Client[CLIENT_COUNT];
        for (int i = 0; i < CLIENT_COUNT; i++) {
            clients[i] = Client.builder()
                    .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameService.class, BenchConn.class)
                    .build();
            clients[i].start();
        }
        Thread.sleep(1000);

        // Pre-warm each client's send path.
        for (int i = 0; i < 2_000; i++) {
            clients[i % CLIENT_COUNT].send(new PositionUpdate(1f, 2f, 3f, 0.5f));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (clients != null) {
            for (var c : clients) {
                if (c != null) c.close();
            }
        }
        if (server != null) server.close();
    }

    @Benchmark
    public void positionUpdate_manyClients() {
        // Round-robin across 100 clients — touches all SelectionKeys over time.
        int i = cursor;
        cursor = (i + 1) % CLIENT_COUNT;
        clients[i].send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }
}
