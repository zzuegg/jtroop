package bench.net;

import bench.GameMessages;
import jtroop.client.Client;
import jtroop.codec.CodecRegistry;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: Our net module game server processing position updates + chat messages.
 * Measures throughput and GC allocation rate — targeting 0 B/op.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class NetGameBenchmark {

    // Message records
    public record PositionUpdate(float x, float y, float z, float yaw) {}
    public record ChatMessage(String text, int room) {}
    public record MoveAck(int ok) {}

    // Service contract
    public interface GameService {
        void position(PositionUpdate pos);
        void chat(ChatMessage msg);
    }

    // Handler
    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate pos, ConnectionId sender) {
            // Process position — in real game: update world state
        }

        @OnMessage void chat(ChatMessage msg, ConnectionId sender) {
            // Process chat — in real game: broadcast
        }
    }

    record BenchConn(int v) {}

    private Server server;
    private Client client;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var handler = new GameHandler();
        server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500); // wait for connection
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Benchmark
    public void positionUpdate() {
        client.send(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f));
    }

    @Benchmark
    public void positionUpdate_blocking() {
        client.sendBlocking(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f));
    }

    @Benchmark
    public void chatMessage() {
        client.send(new ChatMessage(GameMessages.CHAT_TEXT, 1));
    }

    @Benchmark
    public void chatMessage_blocking() {
        client.sendBlocking(new ChatMessage(GameMessages.CHAT_TEXT, 1));
    }

    @Benchmark
    public void mixedTraffic() {
        // 80% position updates, 20% chat (typical game workload)
        for (int i = 0; i < 10; i++) {
            if (i < 8) {
                client.send(new PositionUpdate(i * 0.1f, i * 0.2f, i * 0.3f, i * 0.01f));
            } else {
                client.send(new ChatMessage(GameMessages.CHAT_TEXT, i));
            }
        }
    }
}
