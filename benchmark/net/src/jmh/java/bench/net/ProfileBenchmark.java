package bench.net;

import jtroop.client.Client;
import jtroop.codec.CodecRegistry;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmarks isolating individual components to find allocation hotspots.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class ProfileBenchmark {

    public record PosUpdate(float x, float y, float z, float yaw) {}

    private CodecRegistry codec;
    private ByteBuffer encodeBuf;
    private ByteBuffer wireBuf;
    private Pipeline pipeline;
    private PosUpdate msg;
    private WriteBuffer wb;

    @Setup(Level.Trial)
    public void setup() {
        codec = new CodecRegistry();
        codec.register(PosUpdate.class);
        encodeBuf = ByteBuffer.allocate(256);
        wireBuf = ByteBuffer.allocate(512);
        pipeline = new Pipeline(new FramingLayer());
        msg = new PosUpdate(1f, 2f, 3f, 0.5f);
        wb = new WriteBuffer(encodeBuf);
    }

    /** Isolate: just codec encode (record → ByteBuffer) */
    @Benchmark
    public void codecEncodeOnly() {
        encodeBuf.clear();
        codec.encode(msg, wb);
    }

    /** Isolate: codec encode + pipeline outbound (encode → frame) */
    @Benchmark
    public void codecEncodePlusPipeline() {
        encodeBuf.clear();
        codec.encode(msg, wb);
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
    }

    /** Isolate: full encode + pipeline + decode roundtrip (no network) */
    @Benchmark
    public Object codecRoundtrip() {
        encodeBuf.clear();
        codec.encode(msg, wb);
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();
        var frame = pipeline.decodeInbound(wireBuf);
        return codec.decode(new ReadBuffer(frame));
    }

    /** Isolate: pipeline decode only. Targets the allocation cost of
     *  FramingLayer.decodeInbound — previously 56 B/op from wire.slice(). */
    @Benchmark
    public Object pipelineDecode() {
        // Prepare a framed wire buffer (setup excluded from the hot path is
        // unrealistic; include it so the frame is fresh each call). The encode
        // steps do not allocate, so any B/op reported is from decode.
        encodeBuf.clear();
        codec.encode(msg, wb);
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();
        return pipeline.decodeInbound(wireBuf);
    }

    /** Isolate: full send path (encode + pipeline + stageWrite, no actual network) */
    record BenchConn(int v) {}

    interface BenchSvc { void pos(PosUpdate p); }

    @Handles(BenchSvc.class)
    public static class BenchHandler {
        @OnMessage void pos(PosUpdate p, ConnectionId s) {}
    }

    private Client client;

    @Setup(Level.Trial)
    public void setupClient() throws Exception {
        var handler = new BenchHandler();
        var server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(BenchSvc.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500);
    }

    /** Full send through actual server+client (same as NetGameBenchmark.positionUpdate) */
    @Benchmark
    public void fullSend() {
        client.send(new PosUpdate(1f, 2f, 3f, 0.5f));
    }
}
