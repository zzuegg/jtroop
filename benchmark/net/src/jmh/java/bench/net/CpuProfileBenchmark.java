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
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * CPU-focused benchmarks — isolate where cycles are spent.
 * Run with: -prof perfnorm (Linux) or -prof comp (JIT stats)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class CpuProfileBenchmark {

    public record PosUpdate(float x, float y, float z, float yaw) {}
    public record ChatMsg(String text, int room) {}

    // --- Shared state ---
    private CodecRegistry codec;
    private ByteBuffer encodeBuf;
    private ByteBuffer decodeBuf;
    private ByteBuffer wireBuf;
    private Pipeline pipeline;
    private WriteBuffer wb;
    private PosUpdate posMsg;
    private ChatMsg chatMsg;

    // --- Full stack state ---
    record BenchConn(int v) {}
    interface BenchSvc {
        void pos(PosUpdate p);
        void chat(ChatMsg m);
    }

    @Handles(BenchSvc.class)
    public static class BenchHandler {
        @OnMessage void pos(PosUpdate p, ConnectionId s) {}
        @OnMessage void chat(ChatMsg m, ConnectionId s) {}
    }

    private Client client;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        codec = new CodecRegistry();
        codec.register(PosUpdate.class);
        codec.register(ChatMsg.class);
        encodeBuf = ByteBuffer.allocate(512);
        decodeBuf = ByteBuffer.allocate(512);
        wireBuf = ByteBuffer.allocate(1024);
        pipeline = new Pipeline(new FramingLayer());
        wb = new WriteBuffer(encodeBuf);
        posMsg = new PosUpdate(1f, 2f, 3f, 0.5f);
        chatMsg = new ChatMsg("Hello from player! This is a typical chat message.", 1);

        // Pre-warm: encode+decode 10K times to trigger JIT
        for (int i = 0; i < 10_000; i++) {
            encodeBuf.clear();
            codec.encode(posMsg, wb);
            encodeBuf.flip();
            wireBuf.clear();
            pipeline.encodeOutbound(encodeBuf, wireBuf);
            wireBuf.flip();
            var frame = pipeline.decodeInbound(wireBuf);
            if (frame != null) codec.decode(new ReadBuffer(frame));
        }

        // Full stack setup
        var handler = new BenchHandler();
        var server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .build();
        server.start();

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", server.port(BenchConn.class)),
                        new FramingLayer())
                .addService(BenchSvc.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500);
    }

    // --- Micro: isolate each step ---

    @Benchmark
    public void cpu_codecEncode(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(posMsg, wb);
        bh.consume(encodeBuf.position());
    }

    @Benchmark
    public void cpu_pipelineEncode(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(posMsg, wb);
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        bh.consume(wireBuf.position());
    }

    @Benchmark
    public void cpu_pipelineDecode(Blackhole bh) {
        // Pre-fill wire buffer with a framed message
        encodeBuf.clear();
        codec.encode(posMsg, wb);
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();

        var frame = pipeline.decodeInbound(wireBuf);
        bh.consume(frame);
    }

    @Benchmark
    public void cpu_codecDecode(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(posMsg, wb);
        encodeBuf.flip();
        wireBuf.clear();
        pipeline.encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();
        var frame = pipeline.decodeInbound(wireBuf);
        var decoded = codec.decode(new ReadBuffer(frame));
        bh.consume(decoded);
    }

    @Benchmark
    public void cpu_fullSendPos(Blackhole bh) {
        client.send(posMsg);
    }

    @Benchmark
    public void cpu_fullSendChat(Blackhole bh) {
        client.send(chatMsg);
    }

    @Benchmark
    public void cpu_hashMapLookup(Blackhole bh) {
        // Isolate the codec type lookup cost
        bh.consume(codec.typeId(PosUpdate.class));
    }

    @Benchmark
    public void cpu_byteBufferPut4Floats(Blackhole bh) {
        encodeBuf.clear();
        encodeBuf.putFloat(1f);
        encodeBuf.putFloat(2f);
        encodeBuf.putFloat(3f);
        encodeBuf.putFloat(0.5f);
        bh.consume(encodeBuf.position());
    }
}
