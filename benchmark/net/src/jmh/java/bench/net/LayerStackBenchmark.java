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
import java.util.zip.Deflater;

/**
 * positionUpdate with various layer stacks:
 *
 * <ul>
 *   <li>{@code positionUpdate_framingOnly} — FramingLayer only (baseline)</li>
 *   <li>{@code positionUpdate_compressed} — FramingLayer + CompressionLayer (level sweep)</li>
 *   <li>{@code positionUpdate_compressedBypass} — CompressionLayer with small-payload bypass</li>
 *   <li>{@code positionUpdate_encrypted} — FramingLayer + EncryptionLayer</li>
 * </ul>
 *
 * <h3>Compression level sweep</h3>
 * {@code positionUpdate_compressed} sweeps zlib levels 0/1/3/6/9 via {@code @Param}.
 * Level 0 (store-only) isolates our code overhead from zlib CPU cost.
 * Level 1 (fastest) is the sweet spot for small payloads.
 * Level 6 (default) is the original baseline (~940 ops/ms, 32 B/op).
 *
 * <h3>Small-payload bypass</h3>
 * {@code positionUpdate_compressedBypass} uses {@code minCompressSize=64},
 * which skips zlib entirely for the 16-byte PositionUpdate. This should
 * approach framing-only throughput since the layer becomes a 1-byte flag
 * prefix + memcpy.
 *
 * <h3>Compression crossover analysis</h3>
 * For a 16-byte PositionUpdate payload, zlib adds ~11 bytes of header
 * overhead (2-byte header + 4-byte ADLER32 + framing). Compressed output
 * is typically 24-28 bytes — <em>larger</em> than the 16-byte input.
 * The crossover point where compression becomes beneficial is typically
 * 64-128 bytes for low-entropy game data (floats with limited range).
 * For high-entropy data (random bytes, encrypted), the crossover is
 * even higher (~256+ bytes). The bypass threshold of 64 bytes is
 * conservative: it avoids the worst-case expansion while still
 * compressing payloads large enough to benefit.
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

    /**
     * Compression level for the {@code positionUpdate_compressed} benchmark.
     * <ul>
     *   <li>0 = store only (no compression, isolates zlib overhead)</li>
     *   <li>1 = fastest compression</li>
     *   <li>3 = fast compromise</li>
     *   <li>6 = default (original baseline)</li>
     *   <li>9 = best compression (slowest)</li>
     * </ul>
     */
    @Param({"0", "1", "3", "6", "9"})
    int compressionLevel;

    record PlainConn(int v) {}
    record CompressedConn(int v) {}
    record BypassConn(int v) {}
    record EncryptedConn(int v) {}

    private Server plainServer;
    private Client plainClient;
    private Server compServer;
    private Client compClient;
    private Server bypassServer;
    private Client bypassClient;
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

        // Compressed with parameterised level; minCompressSize=0 forces compression
        // on all payloads regardless of size, to measure pure zlib cost.
        compServer = Server.builder()
                .listen(CompressedConn.class, Transport.tcp(0),
                        new FramingLayer(), new CompressionLayer(compressionLevel, 0))
                .addService(new GameHandler(), CompressedConn.class)
                .build();
        compServer.start();
        compClient = Client.builder()
                .connect(CompressedConn.class,
                        Transport.tcp("localhost", compServer.port(CompressedConn.class)),
                        new FramingLayer(), new CompressionLayer(compressionLevel, 0))
                .addService(GameService.class, CompressedConn.class)
                .build();
        compClient.start();

        // Bypass variant: default level but minCompressSize=64, so 16-byte
        // PositionUpdate skips zlib entirely.
        bypassServer = Server.builder()
                .listen(BypassConn.class, Transport.tcp(0),
                        new FramingLayer(), new CompressionLayer(Deflater.DEFAULT_COMPRESSION, 64))
                .addService(new GameHandler(), BypassConn.class)
                .build();
        bypassServer.start();
        bypassClient = Client.builder()
                .connect(BypassConn.class,
                        Transport.tcp("localhost", bypassServer.port(BypassConn.class)),
                        new FramingLayer(), new CompressionLayer(Deflater.DEFAULT_COMPRESSION, 64))
                .addService(GameService.class, BypassConn.class)
                .build();
        bypassClient.start();

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
            bypassClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
            encClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (plainClient != null) plainClient.close();
        if (plainServer != null) plainServer.close();
        if (compClient != null) compClient.close();
        if (compServer != null) compServer.close();
        if (bypassClient != null) bypassClient.close();
        if (bypassServer != null) bypassServer.close();
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

    /**
     * CompressionLayer with bypass: 16-byte payload &lt; 64-byte threshold,
     * so zlib is skipped. Should approach framingOnly throughput.
     */
    @Benchmark
    public void positionUpdate_compressedBypass() {
        bypassClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }

    @Benchmark
    public void positionUpdate_encrypted() {
        encClient.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }
}
