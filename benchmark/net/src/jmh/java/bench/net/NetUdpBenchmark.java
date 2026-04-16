package bench.net;

import jtroop.client.Client;
import jtroop.codec.CodecRegistry;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.layers.AckLayer;
import jtroop.pipeline.layers.DuplicateFilterLayer;
import jtroop.pipeline.layers.SequencingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: UDP hot paths.
 *
 * <ul>
 *   <li>{@code udpPositionUpdate} — fire-and-forget UDP position packets through
 *       the full {@link Client}/{@link Server} pipeline with {@code @Datagram}
 *       routing. Equivalent to {@code NetGameBenchmark.positionUpdate} but over
 *       UDP instead of TCP.</li>
 *   <li>{@code udpReliable} — same payload + {@code SequencingLayer +
 *       DuplicateFilterLayer + AckLayer} driving a raw {@link DatagramChannel}.
 *       Measures the per-packet allocation cost of the reliability layers.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class NetUdpBenchmark {

    // Match NetGameBenchmark.PositionUpdate payload exactly (16 B: 4 floats).
    public record PositionUpdate(float x, float y, float z, float yaw) {}

    public interface GameService {
        @Datagram void position(PositionUpdate pos);
    }

    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate pos, ConnectionId sender) {}
    }

    public record BenchConn(int v) {}

    // ---- fire-and-forget UDP via Client/Server ----
    private Server server;
    private Client client;

    // ---- raw reliable-UDP state ----
    private DatagramChannel sendChannel;
    private DatagramChannel recvChannel;
    private CodecRegistry reliableCodec;
    private AckLayer clientAck;
    private SequencingLayer clientSeq;
    private DuplicateFilterLayer serverDupFilter;
    private AckLayer serverAck;
    private SequencingLayer serverSeq;
    private ByteBuffer reliableEncode;
    private ByteBuffer reliableAfterSeq;
    private ByteBuffer reliableWire;
    private ByteBuffer reliableRecv;
    private ByteBuffer recycledAckBuf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var handler = new GameHandler();
        // Single-peer benchmark: use connected-UDP transport. Server promotes
        // its DatagramChannel to connected on first packet → subsequent reads
        // skip the per-packet InetSocketAddress allocation (~32 B/op).
        server = Server.builder()
                .listen(BenchConn.class, Transport.udpConnected(0))
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.udpPort(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.udpConnected("localhost", port))
                .addService(GameService.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(200);

        // --- reliable UDP raw setup: sender + receiver on localhost ---
        recvChannel = DatagramChannel.open();
        recvChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        recvChannel.configureBlocking(false);
        int recvPort = ((InetSocketAddress) recvChannel.getLocalAddress()).getPort();

        sendChannel = DatagramChannel.open();
        sendChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        sendChannel.configureBlocking(false);
        sendChannel.connect(new InetSocketAddress("127.0.0.1", recvPort));
        // Connect recvChannel back to sendChannel so we can use read() instead
        // of receive() on the hot path — avoids the per-packet
        // InetSocketAddress allocation that DatagramChannel.receive() makes.
        var sendAddr = (InetSocketAddress) sendChannel.getLocalAddress();
        recvChannel.connect(new InetSocketAddress("127.0.0.1", sendAddr.getPort()));

        reliableCodec = new CodecRegistry();
        reliableCodec.register(PositionUpdate.class);
        // Long retransmit timeout so the benchmark doesn't fire retransmits
        // (we're measuring steady-state encode/decode cost, not recovery).
        clientAck = new AckLayer(2_000);
        clientSeq = new SequencingLayer();
        serverDupFilter = new DuplicateFilterLayer(1024);
        serverAck = new AckLayer(2_000);
        serverSeq = new SequencingLayer();

        reliableEncode = ByteBuffer.allocate(512);
        reliableAfterSeq = ByteBuffer.allocate(512);
        reliableWire = ByteBuffer.allocate(512);
        reliableRecv = ByteBuffer.allocate(2048);
        recycledAckBuf = ByteBuffer.allocate(4);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close();
        if (sendChannel != null) sendChannel.close();
        if (recvChannel != null) recvChannel.close();
    }

    /**
     * UDP fire-and-forget: encode + datagram send through the full Client/Server
     * stack. Equivalent to {@code NetGameBenchmark.positionUpdate} but over
     * {@code Transport.udp(...)} instead of TCP+framing.
     */
    @Benchmark
    public void udpPositionUpdate() {
        client.send(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f));
    }

    /**
     * Reliable UDP: SequencingLayer + DuplicateFilterLayer + AckLayer driven over
     * a raw DatagramChannel. Exercises exactly the code paths a reliable-UDP
     * client uses on every outbound packet.
     *
     * <p>Sender side (per call):
     * <pre>
     *   codec.encode → payload buffer
     *   SequencingLayer.encodeOutbound  (prepend seq)
     *   AckLayer.encodeOutbound         (prepend seq + copy into unacked SoA)
     *   DatagramChannel.write
     *   DatagramChannel.read            (connected — no InetSocketAddress alloc)
     *   DuplicateFilterLayer.decodeInbound
     *   AckLayer.decodeInbound
     *   SequencingLayer.decodeInbound
     * </pre>
     */
    @Benchmark
    public void udpReliable(Blackhole bh) throws Exception {
        // --- sender encodes message ---
        reliableEncode.clear();
        reliableCodec.encode(
                new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f),
                new WriteBuffer(reliableEncode));
        reliableEncode.flip();

        // --- apply SequencingLayer then AckLayer ---
        reliableAfterSeq.clear();
        clientSeq.encodeOutbound(reliableEncode, reliableAfterSeq);
        reliableAfterSeq.flip();

        reliableWire.clear();
        clientAck.encodeOutbound(reliableAfterSeq, reliableWire);
        reliableWire.flip();

        // --- actual UDP send ---
        sendChannel.write(reliableWire);

        // --- drain one datagram on the receiver side (reliable path includes
        //     the receive + dedupe + ack decode).  If the datagram hasn't
        //     arrived yet, spin briefly — localhost loopback is ~µs. Uses
        //     read() on a connected DatagramChannel, which (unlike receive())
        //     does not allocate an InetSocketAddress per packet. ---
        reliableRecv.clear();
        int n = 0;
        long dl = System.nanoTime() + 1_000_000L; // 1 ms budget
        while (n <= 0 && System.nanoTime() < dl) {
            n = recvChannel.read(reliableRecv);
        }
        if (n <= 0) return; // loss — caller will retransmit on the next iteration
        reliableRecv.flip();

        // --- receiver pipeline: DuplicateFilter → Ack → Sequencing ---
        var afterDup = serverDupFilter.decodeInbound(reliableRecv);
        if (afterDup == null) return;
        var afterAck = serverAck.decodeInbound(afterDup);
        if (afterAck == null) return;
        var afterSeq = serverSeq.decodeInbound(afterAck);
        bh.consume(afterSeq);

        // Drain sender's unacked tracking periodically — prevents the SoA
        // from filling up during a long benchmark run. Uses a pre-allocated
        // 4-byte buffer so even this cold branch is zero-alloc.
        if ((clientAck.unackedCount() & 0xFF) == 0xFF) {
            recycledAckBuf.clear();
            recycledAckBuf.putInt(clientSeq.nextSendSeq() - 1);
            recycledAckBuf.flip();
            clientAck.processAck(recycledAckBuf);
        }
    }
}
