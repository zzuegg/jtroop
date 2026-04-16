package bench.net;

import bench.GameMessages;
import jtroop.client.Client;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.Handles;
import jtroop.service.OnMessage;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Percentile-distribution latency benchmarks using {@code Mode.SampleTime}.
 *
 * <p>JMH's SampleTime mode records every invocation's wall-clock duration and
 * reports p50/p90/p99/p99.9/p99.99 histograms. This complements the existing
 * {@link LatencyBenchmark} (AverageTime) by exposing tail latency and jitter
 * that averages hide.
 *
 * <p>Benchmarks:
 * <ul>
 *   <li>{@link #positionUpdate_latency} — fire-and-forget {@code client.send()}</li>
 *   <li>{@link #positionUpdate_blocking_latency} — blocking encode+stage+flush+write</li>
 *   <li>{@link #requestResponse_latency} — full RPC round-trip via {@code client.request()}</li>
 *   <li>{@link #chatMessage_latency} — fire-and-forget with String payload</li>
 *   <li>{@link #sustainedLoad_latency} — blocking send under 10K msg/sec background load</li>
 * </ul>
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class LatencyProfileBenchmark {

    // --- Message records ---

    public record PositionUpdate(float x, float y, float z, float yaw) {}
    public record ChatMessage(String text, int room) {}
    public record EchoMsg(int seq) {}
    public record EchoAck(int seq) {}

    // --- Service contracts ---

    public interface GameService {
        void position(PositionUpdate pos);
        void chat(ChatMessage msg);
    }

    public interface EchoService {
        EchoAck echo(EchoMsg msg);
    }

    // --- Handlers ---

    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate p, ConnectionId sender) {}
        @OnMessage void chat(ChatMessage msg, ConnectionId sender) {}
    }

    @Handles(EchoService.class)
    public static class EchoHandler {
        @OnMessage EchoAck echo(EchoMsg msg, ConnectionId sender) {
            return new EchoAck(msg.seq());
        }
    }

    record BenchConn(int v) {}
    record LoadConn(int v) {}

    // ---- Shared server + client state (no background load) ------------------

    @State(Scope.Benchmark)
    public static class BaseState {
        Server server;
        Client client;
        EchoService echo;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            server = Server.builder()
                    .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                    .addService(new GameHandler(), BenchConn.class)
                    .addService(new EchoHandler(), BenchConn.class)
                    .build();
            server.start();
            int port = server.port(BenchConn.class);

            client = Client.builder()
                    .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameService.class, BenchConn.class)
                    .addService(EchoService.class, BenchConn.class)
                    .build();
            client.start();
            Thread.sleep(500);

            echo = client.service(EchoService.class);

            // Pre-warm all paths (CLAUDE.md rule #8): 10K invocations so C2
            // compiles with real branch profiles.
            for (int i = 0; i < 10_000; i++) {
                client.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
            }
            Thread.sleep(100);
            for (int i = 0; i < 10_000; i++) {
                client.sendBlocking(new PositionUpdate(1f, 2f, 3f, 0.5f));
            }
            for (int i = 0; i < 10_000; i++) {
                echo.echo(new EchoMsg(i));
            }
            for (int i = 0; i < 10_000; i++) {
                client.send(new ChatMessage(GameMessages.CHAT_TEXT, 1));
            }
            Thread.sleep(100);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (client != null) client.close();
            if (server != null) server.close();
        }
    }

    // ---- Sustained-load state: separate client for background sender --------

    @State(Scope.Benchmark)
    public static class LoadState {
        Server server;
        Client benchClient;   // measured client (blocking sends)
        Client loadClient;    // background fire-and-forget sender
        AtomicBoolean loadRunning;
        Thread loadThread;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            server = Server.builder()
                    .listen(LoadConn.class, Transport.tcp(0), new FramingLayer())
                    .addService(new GameHandler(), LoadConn.class)
                    .build();
            server.start();
            int port = server.port(LoadConn.class);

            benchClient = Client.builder()
                    .connect(LoadConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameService.class, LoadConn.class)
                    .build();
            benchClient.start();

            loadClient = Client.builder()
                    .connect(LoadConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameService.class, LoadConn.class)
                    .build();
            loadClient.start();
            Thread.sleep(500);

            // Pre-warm both clients.
            for (int i = 0; i < 10_000; i++) {
                benchClient.sendBlocking(new PositionUpdate(1f, 2f, 3f, 0.5f));
            }
            for (int i = 0; i < 10_000; i++) {
                loadClient.send(new PositionUpdate(0f, 0f, 0f, 0f));
            }
            Thread.sleep(100);

            // Start sustained-load background sender: 10K msgs/sec on a
            // separate client so write-buffer contention is realistic but
            // does not overflow the measured client's buffer.
            loadRunning = new AtomicBoolean(true);
            loadThread = new Thread(() -> {
                long intervalNs = 100_000; // 100 us = 10K/sec
                while (loadRunning.get()) {
                    long start = System.nanoTime();
                    try {
                        loadClient.send(new PositionUpdate(0f, 0f, 0f, 0f));
                    } catch (Exception e) {
                        return;
                    }
                    long elapsed = System.nanoTime() - start;
                    long sleepNs = intervalNs - elapsed;
                    if (sleepNs > 0) {
                        long deadline = System.nanoTime() + sleepNs;
                        while (System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                        }
                    }
                }
            }, "sustained-load-sender");
            loadThread.setDaemon(true);
            loadThread.start();
        }

        @TearDown(Level.Trial)
        public void teardown() throws Exception {
            if (loadRunning != null) loadRunning.set(false);
            if (loadThread != null) loadThread.join(2000);
            if (benchClient != null) benchClient.close();
            if (loadClient != null) loadClient.close();
            if (server != null) server.close();
        }
    }

    // --- Benchmarks ---

    /**
     * Fire-and-forget send latency. Measures encode + stage into the EventLoop's
     * MPSC queue. Does NOT wait for the bytes to hit the socket.
     */
    @Benchmark
    public void positionUpdate_latency(BaseState s) {
        s.client.send(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }

    /**
     * Blocking send latency. Measures the full path: encode on caller thread,
     * stage into EventLoop queue, block until EventLoop flushes bytes to the
     * socket. Captures queuing delay + IO write time.
     */
    @Benchmark
    public void positionUpdate_blocking_latency(BaseState s) {
        s.client.sendBlocking(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }

    /**
     * Full RPC round-trip latency: encode request, send, server decode +
     * dispatch + encode response, client decode response. The complete
     * user-perceived request/response cycle.
     */
    @Benchmark
    public EchoAck requestResponse_latency(BaseState s) {
        return s.echo.echo(new EchoMsg(1));
    }

    /**
     * Chat message fire-and-forget latency. Includes String encoding overhead
     * (UTF-8 byte[] from String). Shows the cost of variable-length payloads
     * vs fixed-width position updates.
     */
    @Benchmark
    public void chatMessage_latency(BaseState s) {
        s.client.send(new ChatMessage(GameMessages.CHAT_TEXT, 1));
    }

    /**
     * Blocking send latency under sustained background load (10K msgs/sec).
     * A separate client sends fire-and-forget position updates at a fixed rate
     * to the same server, creating realistic event-loop contention. This
     * benchmark measures one blocking send's latency in that environment.
     *
     * <p>Reveals queueing delays that pure-throughput and unloaded latency
     * benchmarks hide. Compare p99 here vs {@link #positionUpdate_blocking_latency}
     * to quantify the cost of contention.
     */
    @Benchmark
    public void sustainedLoad_latency(LoadState s) {
        s.benchClient.sendBlocking(new PositionUpdate(1f, 2f, 3f, 0.5f));
    }
}
