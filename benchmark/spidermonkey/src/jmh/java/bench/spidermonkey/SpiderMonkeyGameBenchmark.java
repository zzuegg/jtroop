package bench.spidermonkey;

import bench.GameMessages;
import com.jme3.network.*;
import com.jme3.network.serializing.Serializable;
import com.jme3.network.serializing.Serializer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: jMonkeyEngine SpiderMonkey game server.
 * Fire-and-forget sends (no blocking) for fair comparison with Netty and jtroop.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class SpiderMonkeyGameBenchmark {

    @Serializable
    public static class PositionUpdateMsg extends AbstractMessage {
        public float x, y, z, yaw;
        public PositionUpdateMsg() { setReliable(true); }
        public PositionUpdateMsg(float x, float y, float z, float yaw) {
            this();
            this.x = x; this.y = y; this.z = z; this.yaw = yaw;
        }
    }

    @Serializable
    public static class ChatMsg extends AbstractMessage {
        public String text;
        public int room;
        public ChatMsg() { setReliable(true); }
        public ChatMsg(String text, int room) {
            this();
            this.text = text; this.room = room;
        }
    }

    private Server server;
    private Client client;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Serializer.registerClass(PositionUpdateMsg.class);
        Serializer.registerClass(ChatMsg.class);

        int port = 44557;
        server = Network.createServer(port);
        server.addMessageListener((source, message) -> {
            // Process — just receive
        }, PositionUpdateMsg.class, ChatMsg.class);
        server.start();

        client = Network.connectToServer("localhost", port);
        client.start();

        Thread.sleep(500);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (client != null) { try { client.close(); } catch (Exception _) {} }
        if (server != null) { try { server.close(); } catch (Exception _) {} }
    }

    @Benchmark
    public void positionUpdate() {
        client.send(new PositionUpdateMsg(1.0f, 2.0f, 3.0f, 0.5f));
    }

    @Benchmark
    public void chatMessage() {
        client.send(new ChatMsg(GameMessages.CHAT_TEXT, 1));
    }

    @Benchmark
    public void mixedTraffic() {
        for (int i = 0; i < 10; i++) {
            if (i < 8) {
                client.send(new PositionUpdateMsg(i * 0.1f, i * 0.2f, i * 0.3f, i * 0.01f));
            } else {
                client.send(new ChatMsg(GameMessages.CHAT_TEXT, i));
            }
        }
    }
}
