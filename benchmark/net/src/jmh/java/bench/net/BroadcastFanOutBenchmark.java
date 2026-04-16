package bench.net;

import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.Broadcast;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Broadcast fan-out sweep. A trigger client sends one message per op; the
 * server-side handler calls {@code broadcast.send(...)} which delivers to
 * every connected client.
 *
 * <p>Variants:
 * <ul>
 *   <li>{@code broadcast_1}   — N=1 recipient (baseline)</li>
 *   <li>{@code broadcast_10}  — N=10 recipients</li>
 *   <li>{@code broadcast_100} — N=100 recipients</li>
 * </ul>
 *
 * <p>Signals:
 * <ul>
 *   <li>ops/ms should scale ≈1/N (doubling recipients halves rate).
 *       Sub-linear scaling (e.g. 1/N^1.5) points at contention on the
 *       server-side connection map or per-peer socket writes.</li>
 *   <li>B/op proportional to N = per-recipient allocation (should be 0 after
 *       iterating via {@code sessions.forEachActive}).</li>
 *   <li>Sub-linear throughput with 0 B/op = CPU-bound memcpy / syscall
 *       overhead, not allocation — look at native {@code SocketChannel.write}
 *       cost.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class BroadcastFanOutBenchmark {

    public record Trigger(int seq) {}
    public record Pushed(int seq) {}

    public interface TriggerService {
        void trigger(Trigger t);
    }

    @Handles(TriggerService.class)
    public static class FanOutHandler {
        @OnMessage void trigger(Trigger t, ConnectionId sender, Broadcast broadcast) {
            broadcast.send(new Pushed(t.seq()));
        }
    }

    record Conn1(int v) {}
    record Conn10(int v) {}
    record Conn100(int v) {}

    private Server server1;
    private Server server10;
    private Server server100;
    private Client trigger1;
    private Client trigger10;
    private Client trigger100;
    private Client[] listeners1;
    private Client[] listeners10;
    private Client[] listeners100;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        server1 = buildServer(Conn1.class);
        trigger1 = buildTriggerClient(Conn1.class, server1.port(Conn1.class));
        listeners1 = connectListeners(Conn1.class, server1.port(Conn1.class), 1);

        server10 = buildServer(Conn10.class);
        trigger10 = buildTriggerClient(Conn10.class, server10.port(Conn10.class));
        listeners10 = connectListeners(Conn10.class, server10.port(Conn10.class), 10);

        server100 = buildServer(Conn100.class);
        trigger100 = buildTriggerClient(Conn100.class, server100.port(Conn100.class));
        listeners100 = connectListeners(Conn100.class, server100.port(Conn100.class), 100);

        Thread.sleep(1500);

        for (int i = 0; i < 2_000; i++) {
            trigger1.send(new Trigger(i));
            trigger10.send(new Trigger(i));
            trigger100.send(new Trigger(i));
        }
        Thread.sleep(500);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        closeAll(listeners1);
        closeAll(listeners10);
        closeAll(listeners100);
        if (trigger1 != null) trigger1.close();
        if (trigger10 != null) trigger10.close();
        if (trigger100 != null) trigger100.close();
        if (server1 != null) server1.close();
        if (server10 != null) server10.close();
        if (server100 != null) server100.close();
    }

    private static void closeAll(Client[] arr) {
        if (arr == null) return;
        for (var c : arr) if (c != null) c.close();
    }

    private static Server buildServer(Class<? extends Record> connType) throws Exception {
        var s = Server.builder()
                .listen(connType, Transport.tcp(0), new FramingLayer())
                .addService(new FanOutHandler(), connType)
                .eventLoops(2)
                .build();
        s.start();
        return s;
    }

    private static Client buildTriggerClient(Class<? extends Record> connType, int port) throws Exception {
        var c = Client.builder()
                .connect(connType, Transport.tcp("localhost", port), new FramingLayer())
                .addService(TriggerService.class, connType)
                .onMessage(Pushed.class, p -> {})
                .build();
        c.start();
        return c;
    }

    private static Client[] connectListeners(Class<? extends Record> connType, int port, int n) throws Exception {
        var arr = new Client[n];
        for (int i = 0; i < n; i++) {
            arr[i] = Client.builder()
                    .connect(connType, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(TriggerService.class, connType)
                    .onMessage(Pushed.class, p -> {})
                    .build();
            arr[i].start();
        }
        return arr;
    }

    @Benchmark
    public void broadcast_1() {
        trigger1.send(new Trigger(1));
    }

    @Benchmark
    public void broadcast_10() {
        trigger10.send(new Trigger(1));
    }

    @Benchmark
    public void broadcast_100() {
        trigger100.send(new Trigger(1));
    }
}
