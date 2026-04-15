package bench.net;

import jtroop.client.Client;
import jtroop.codec.CodecRegistry;
import jtroop.core.EventLoop;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Isolate each step of the blocking send path to find allocation source.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class AllocTraceBenchmark {

    public record PosUpdate(float x, float y, float z, float yaw) {}
    record BenchConn(int v) {}
    interface BenchSvc { void pos(PosUpdate p); }

    @Handles(BenchSvc.class)
    public static class BenchHandler {
        @OnMessage void pos(PosUpdate p, ConnectionId s) {}
    }

    private Client client;
    private CodecRegistry codec;
    private ByteBuffer encodeBuf;
    private ByteBuffer wireBuf;
    private Pipeline pipeline;
    private EventLoop eventLoop;
    private int slot;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(new BenchHandler(), BenchConn.class)
                .build();
        server.start();
        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", server.port(BenchConn.class)), new FramingLayer())
                .addService(BenchSvc.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500);

        // Isolated components for micro-benchmarking
        codec = new CodecRegistry();
        codec.register(PosUpdate.class);
        encodeBuf = ByteBuffer.allocate(256);
        wireBuf = ByteBuffer.allocate(512);
        pipeline = new Pipeline(new FramingLayer());

        // Pre-warm
        for (int i = 0; i < 20_000; i++) client.sendBlocking(new PosUpdate(1, 2, 3, 0));
    }

    /** Step 1: just codec encode into heap buffer */
    @Benchmark
    public void step1_codecEncode(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(new PosUpdate(1f, 2f, 3f, 0.5f), new WriteBuffer(encodeBuf));
        bh.consume(encodeBuf.position());
    }

    /** Step 2: codec + pipeline encode */
    @Benchmark
    public void step2_codecPipeline(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(new PosUpdate(1f, 2f, 3f, 0.5f), new WriteBuffer(encodeBuf));
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        bh.consume(wireBuf.position());
    }

    /** Step 3: full send() fire-and-forget */
    @Benchmark
    public void step3_sendFireAndForget() {
        client.send(new PosUpdate(1f, 2f, 3f, 0.5f));
    }

    /** Step 4: full sendBlocking() */
    @Benchmark
    public void step4_sendBlocking() {
        client.sendBlocking(new PosUpdate(1f, 2f, 3f, 0.5f));
    }

    /** Step 5: just stageWrite (no wakeup, no blocking) */
    @Benchmark
    public void step5_stageWriteOnly(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(new PosUpdate(1f, 2f, 3f, 0.5f), new WriteBuffer(encodeBuf));
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();
        // Just the stageWrite, no blocking
        client.send(new PosUpdate(1f, 2f, 3f, 0.5f)); // same as step3
        bh.consume(0);
    }
}
