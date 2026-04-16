package bench.net;

import jtroop.pipeline.layers.HttpLayer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end HTTP benchmark running entirely in-process (no sockets), so the
 * numbers isolate pure CPU cost of the HTTP handler.
 *
 * <ul>
 *   <li>{@code http_decodeGet} — decode a minimal GET request (header only).</li>
 *   <li>{@code http_decodePost} — decode a POST request with a 256-byte body.</li>
 *   <li>{@code http_encodeResponse} — encode a 200 OK response frame (fast
 *       path in HttpLayer — text/plain + keep-alive).</li>
 *   <li>{@code http_roundtripGet} — decode GET + encode 200 OK response in
 *       one op (approximates a full request/response cycle the way an event
 *       loop would run it).</li>
 * </ul>
 *
 * <p>Signals:
 * <ul>
 *   <li>Decode B/op should be 0 (parsing is index-based into a pre-allocated
 *       frame buffer). {@link HttpLayer.ParsedRequest} is only allocated by
 *       the test helper {@link HttpLayer#parseFrame(ByteBuffer)}, never in
 *       the hot decode path.</li>
 *   <li>Encode should hit the fast path in {@code HttpLayer.encodeOutbound}
 *       for 200 OK text/plain keep-alive.</li>
 *   <li>If roundtrip B/op &gt; 0 while decode + encode are both 0, some
 *       phi/EA pessimization is triggered by interleaving the two calls.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class HttpLayerBenchmark {

    private HttpLayer layer;
    private ByteBuffer getWire;
    private ByteBuffer postWire;
    private ByteBuffer respFrame;
    private ByteBuffer out;

    @Setup(Level.Trial)
    public void setup() {
        layer = new HttpLayer();

        var getBytes = ("GET /bench HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        getWire = ByteBuffer.allocate(getBytes.length);
        getWire.put(getBytes);

        var body = new byte[256];
        for (int i = 0; i < body.length; i++) body[i] = (byte) ('a' + (i % 26));
        var postHeader = ("POST /bench HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        postWire = ByteBuffer.allocate(postHeader.length + body.length);
        postWire.put(postHeader);
        postWire.put(body);

        var respBody = "OK".getBytes(StandardCharsets.UTF_8);
        respFrame = HttpLayer.buildResponseFrame(200, "OK", "text/plain", respBody);

        out = ByteBuffer.allocate(4096);

        // Warm up paths.
        for (int i = 0; i < 20_000; i++) {
            resetRead(getWire);
            layer.decodeInbound(getWire);
            out.clear();
            respFrame.position(0);
            layer.encodeOutbound(respFrame, out);
        }
    }

    /** Rewind a prepared wire buffer to be freshly readable on each op. */
    private static void resetRead(ByteBuffer wire) {
        wire.position(0);
        wire.limit(wire.capacity());
    }

    @Benchmark
    public Object http_decodeGet() {
        resetRead(getWire);
        return layer.decodeInbound(getWire);
    }

    @Benchmark
    public Object http_decodePost() {
        resetRead(postWire);
        return layer.decodeInbound(postWire);
    }

    @Benchmark
    public void http_encodeResponse(Blackhole bh) {
        out.clear();
        respFrame.position(0);
        layer.encodeOutbound(respFrame, out);
        bh.consume(out.position());
    }

    /** Full request/response: decode GET then encode 200 OK — pure CPU path. */
    @Benchmark
    public void http_roundtripGet(Blackhole bh) {
        resetRead(getWire);
        var frame = layer.decodeInbound(getWire);
        bh.consume(frame);
        out.clear();
        respFrame.position(0);
        layer.encodeOutbound(respFrame, out);
        bh.consume(out.position());
    }
}
