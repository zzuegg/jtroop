package bench.net;

import jtroop.client.Client;
import jtroop.pipeline.layers.CompressionLayer;
import jtroop.pipeline.layers.EncryptionLayer;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.concurrent.TimeUnit;

/**
 * positionUpdate with various layer stacks:
 *
 * <ul>
 *   <li>{@code positionUpdate_framingOnly} — FramingLayer only (baseline)</li>
 *   <li>{@code positionUpdate_compressed} — FramingLayer + CompressionLayer</li>
 *   <li>{@code positionUpdate_encrypted} — FramingLayer + EncryptionLayer</li>
 * </ul>
 *
 * <p>Signals: the delta vs framingOnly quantifies the pure cost of each layer
 * on the hot path. Both {@link CompressionLayer} and {@link EncryptionLayer}
 * currently allocate per-message byte arrays — expect &gt;&gt;0 B/op. If layer
 * allocation is fixed in a future round, this benchmark is the regression guard.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class LayerStackBenchmark {

    public record PositionUpdate(float x, float y, float z, float yaw) {}

    public interface GameService {
        void position(PositionUpdate p);
    }

    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate p, ConnectionId s) {}
    }

    record PlainConn(int v) {}
    record CompressedConn(int v) {}
    record EncryptedConn(int v) {}

    private Server plainServer;
    private Client plainClient;
    private Server compServer;
    private Client compClient;
    private Server encServer;
    private Client encClient;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        plainServer = Server.builder()
                .listen(PlainConn.class, Transport.tcp(0), new FramingLayer())
                .addService(new GameHandler(), PlainConn.class)
                .build();
        plainServer.start();
        plainClient = Client.builder()
                .connect(PlainConn.class, Transport.tcp("localhost", plainServer.port(PlainConn.class)),
                        new FramingLayer())
                .addService(GameService.class, PlainConn.class)
                .build();
        plainClient.start();

        compServer = Server.builder()
                .listen(CompressedConn.class, Transport.tcp(0),
                        new FramingLayer(), new CompressionLayer())
                .addService(new GameHandler(), CompressedConn.class)
                .build();
        compServer.start();
        compClient = Client.builder()
                .connect(CompressedConn.class,
                        Transport.tcp("localhost", compServer.port(CompressedConn.class)),
                        new FramingLayer(), new CompressionLayer())
                .addService(GameService.class, CompressedConn.class)
                .build();
        compClient.start();

        var key = generateKey();
        encServer = Server.builder()
                .listen(EncryptedConn.class, Transport.tcp(0),
                        new FramingLayer(), new EncryptionLayer(key))
                .addService(new GameHandler(), EncryptedConn.class)
                .build();
        encServer.start();
        encClient = Client.builder()
                .connect(EncryptedConn.class,
                        Transport.tcp("localhost", encServer.port(EncryptedConn.class)),
                        new FramingLayer(), new EncryptionLayer(key))
                .addService(GameService.class, EncryptedConn.class)
                .build();
        encClient.start();

        Thread.sleep(500);

        // Pre-warm every variant.
        for (int i = 0; i < 5_000; i++) {
            plainClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
            compClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
            encClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (plainClient != null) plainClient.close();
        if (plainServer != null) plainServer.close();
        if (compClient != null) compClient.close();
        if (compServer != null) compServer.close();
        if (encClient != null) encClient.close();
        if (encServer != null) encServer.close();
    }

    private static SecretKey generateKey() throws Exception {
        var gen = KeyGenerator.getInstance("AES");
        gen.init(128);
        return gen.generateKey();
    }

    @Benchmark
    public void positionUpdate_framingOnly() {
        plainClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }

    @Benchmark
    public void positionUpdate_compressed() {
        compClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }

    @Benchmark
    public void positionUpdate_encrypted() {
        encClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }
}
