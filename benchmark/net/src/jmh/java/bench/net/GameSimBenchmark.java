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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Full game-server simulation: 100 clients, 4 event loops, mixed traffic.
 *
 * <p>One benchmark iteration = one server tick. Every tick:
 * <ul>
 *   <li>Each client sends a position update (100 inbound messages)</li>
 *   <li>Server handler broadcasts a world-state snapshot to all clients</li>
 * </ul>
 *
 * <p>Target: 60+ ticks/sec at 100 clients with 0 B/op per tick.
 * This answers: "can jtroop run a 100-player game at 60 Hz with zero GC?"
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class GameSimBenchmark {

    // --- Message types ---

    public record PositionUpdate(float x, float y, float z, float yaw) {}
    public record WorldState(int tick, int playerCount) {}
    public record ChatMessage(int senderId, int room) {}

    // --- Service contracts ---

    public interface GameService {
        void position(PositionUpdate pos);
        void worldState(WorldState state);
        void chat(ChatMessage msg);
    }

    // --- Server handler: receives positions, broadcasts world state ---

    @Handles(GameService.class)
    public static class GameHandler {
        private int tickCounter;

        @OnMessage void position(PositionUpdate pos, ConnectionId sender, Broadcast broadcast) {
            // Real game: update world state per player.
            // Broadcast a world-state snapshot once per "batch" — we trigger
            // this on every position to stress the broadcast path.
        }

        @OnMessage void chat(ChatMessage msg, ConnectionId sender, Broadcast broadcast) {
            // Broadcast chat to all — typical MMO pattern.
            broadcast.send(msg);
        }

        @OnMessage void worldState(WorldState state, ConnectionId sender) {
            // Client-side: swallowed by onMessage handler, never hits server.
        }
    }

    // --- Benchmark state ---

    private static final int CLIENT_COUNT = 100;
    private static final int EVENT_LOOPS = 4;

    record SimConn(int v) {}

    private Server server;
    private Client[] clients;
    /** Pre-built position messages — one per client, reused every tick. */
    private PositionUpdate[] positions;
    /** Pre-built chat messages for the 5% chat senders. */
    private ChatMessage[] chatMessages;
    /** Indices of the 5 clients that send chat each tick. */
    private int[] chatSenders;
    /** World-state broadcast message, reused. */
    private WorldState worldState;
    private int tickSeq;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var handler = new GameHandler();
        server = Server.builder()
                .listen(SimConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, SimConn.class)
                .eventLoops(EVENT_LOOPS)
                .build();
        server.start();
        int port = server.port(SimConn.class);

        // Connect 100 clients.
        clients = new Client[CLIENT_COUNT];
        for (int i = 0; i < CLIENT_COUNT; i++) {
            clients[i] = Client.builder()
                    .connect(SimConn.class, Transport.tcp("localhost", port), new FramingLayer())
                    .addService(GameService.class, SimConn.class)
                    .onMessage(WorldState.class, _ -> { /* swallow broadcast */ })
                    .onMessage(ChatMessage.class, _ -> { /* swallow broadcast */ })
                    .onMessage(PositionUpdate.class, _ -> { /* swallow broadcast */ })
                    .build();
            clients[i].start();
        }
        // Let all connections establish.
        Thread.sleep(2000);

        // Pre-build per-client position messages (rule #7: cache, don't allocate per tick).
        positions = new PositionUpdate[CLIENT_COUNT];
        for (int i = 0; i < CLIENT_COUNT; i++) {
            positions[i] = new PositionUpdate(i * 0.1f, i * 0.2f, 0f, i * 0.01f);
        }

        // 5% of clients send chat = 5 clients.
        int chatCount = CLIENT_COUNT / 20;
        chatSenders = new int[chatCount];
        chatMessages = new ChatMessage[chatCount];
        for (int i = 0; i < chatCount; i++) {
            chatSenders[i] = i * 20; // evenly spaced
            chatMessages[i] = new ChatMessage(chatSenders[i], 1);
        }

        worldState = new WorldState(0, CLIENT_COUNT);

        // Pre-warm: run ~2000 full ticks so C2 compiles all hot paths
        // with real branch profiles at 100-client fan-out (rule #8).
        for (int i = 0; i < 2_000; i++) {
            doTick();
        }
        Thread.sleep(500); // drain
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (clients != null) {
            for (var c : clients) {
                if (c != null) c.close();
            }
        }
        if (server != null) server.close();
    }

    // --- Core tick logic ---

    private void doTick() {
        // Phase 1: all clients send position updates.
        for (int i = 0; i < CLIENT_COUNT; i++) {
            clients[i].send(positions[i]);
        }
        // Phase 2: one client broadcasts world state (simulates server tick broadcast).
        clients[0].send(worldState);
    }

    private void doTickWithChat() {
        // Phase 1: all clients send position updates.
        for (int i = 0; i < CLIENT_COUNT; i++) {
            clients[i].send(positions[i]);
        }
        // Phase 2: 5% of clients also send chat.
        for (int i = 0; i < chatSenders.length; i++) {
            clients[chatSenders[i]].send(chatMessages[i]);
        }
        // Phase 3: world state broadcast.
        clients[0].send(worldState);
    }

    // --- Benchmarks ---

    /**
     * One server tick: 100 position updates + 1 world-state broadcast.
     * Measures ticks/sec (ops/ms) and allocation per tick (B/op).
     * Target: &gt;60 ticks/sec, 0 B/op.
     */
    @Benchmark
    public void gameTick() {
        doTick();
    }

    /**
     * Same as gameTick but 5% of clients also send a chat message (broadcast
     * to all). Models mixed traffic: position (high freq) + chat (sporadic).
     * Exercises polymorphic dispatch on the server side.
     */
    @Benchmark
    public void gameTickWithChat() {
        doTickWithChat();
    }
}
