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
 * Payload size sweep: small (16 B of primitive floats), medium (~256 B string),
 * large (~4096 B string). Exercises the full encode → pipeline → send path.
 *
 * <p>Compare ops/ms and B/op across sizes:
 * <ul>
 *   <li>Throughput scaling reveals whether the pipeline is framing-bound
 *       (fixed overhead dominates small payloads) or memcpy-bound
 *       (proportional to bytes moved).</li>
 *   <li>B/op > 0 growing with payload size signals an allocation that copies
 *       the payload (e.g. String UTF-8 encoding, byte[] clone). A constant
 *       B/op means the overhead is per-message, independent of payload.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class PayloadSizeBenchmark {

    // 16B payload — four floats
    public record PosSmall(float x, float y, float z, float yaw) {}

    // Medium: ~256B. A String of 256 ASCII chars encodes to ~256 bytes + 2 length prefix.
    public record PosMedium(float x, float y, float z, float yaw, String payload) {}

    // Large: ~4096B string.
    public record PosLarge(float x, float y, float z, float yaw, String payload) {}

    public interface PayloadService {
        void small(PosSmall p);
        void medium(PosMedium p);
        void large(PosLarge p);
    }

    @Handles(PayloadService.class)
    public static class PayloadHandler {
        @OnMessage void small(PosSmall p, ConnectionId s) {}
        @OnMessage void medium(PosMedium p, ConnectionId s) {}
        @OnMessage void large(PosLarge p, ConnectionId s) {}
    }

    record BenchConn(int v) {}

    private Server server;
    private Client client;
    private String mediumPayload;
    private String largePayload;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        mediumPayload = makeAscii(256);
        largePayload = makeAscii(4096);

        server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(new PayloadHandler(), BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(PayloadService.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        // Warmup: exercise each branch so C2 profiles all three paths.
        for (int i = 0; i < 5_000; i++) {
            client.send(new PosSmall(1f, 2f, 3f, 0.5f));
            client.send(new PosMedium(1f, 2f, 3f, 0.5f, mediumPayload));
            client.send(new PosLarge(1f, 2f, 3f, 0.5f, largePayload));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    private static String makeAscii(int len) {
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) ('a' + (i % 26)));
        return sb.toString();
    }

    @Benchmark
    public void positionUpdate_small() {
        client.send(new PosSmall(1f, 2f, 3f, 0.5f));
    }

    @Benchmark
    public void positionUpdate_medium() {
        client.send(new PosMedium(1f, 2f, 3f, 0.5f, mediumPayload));
    }

    @Benchmark
    public void positionUpdate_large() {
        client.send(new PosLarge(1f, 2f, 3f, 0.5f, largePayload));
    }
}
