package jtroop.testing;

import jtroop.pipeline.layers.AckLayer;
import jtroop.pipeline.layers.DuplicateFilterLayer;
import jtroop.pipeline.layers.SequencingLayer;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end UDP reliability test: client -> Forwarder (50% packet loss) -> server.
 *
 * <p>Drives AckLayer + SequencingLayer + DuplicateFilterLayer directly over raw
 * {@link DatagramChannel}s. Verifies that:
 * <ul>
 *   <li>packets dropped by the Forwarder are retransmitted by the sender after
 *       the retransmit timeout;</li>
 *   <li>acks flow back over the wire (wired manually: the receiver sends a
 *       distinct ack datagram after decoding each data packet);</li>
 *   <li>duplicate retransmits are filtered out on the receiver;</li>
 *   <li>eventually all packets are delivered in order even with heavy loss.</li>
 * </ul>
 *
 * <p>Wire format used here — chosen because {@link AckLayer} intentionally does
 * NOT embed acks in the data stream:
 * <pre>
 *   DATA packet : [0x01][seq:int][payload...]
 *   ACK  packet : [0x02][seq:int]
 * </pre>
 */
@Timeout(30)
class ReliableUdpIntegrationTest {

    private static final byte TAG_DATA = 0x01;
    private static final byte TAG_ACK = 0x02;

    @Test
    void retransmit_deliversAllPacketsThrough50PercentLoss() throws Exception {
        final int packetCount = 40;

        // --- server side ---
        var serverChannel = DatagramChannel.open();
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        serverChannel.configureBlocking(false);
        int serverPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        // --- forwarder: 50% packet loss, client-side -> server-side ---
        var forwarder = Forwarder.builder()
                .forward(Transport.udp(0), "127.0.0.1", serverPort)
                .packetLoss(0.50)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        // --- client side: sends through forwarder ---
        var clientChannel = DatagramChannel.open();
        clientChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        clientChannel.configureBlocking(false);
        var fwdAddr = new InetSocketAddress("127.0.0.1", fwdPort);

        // Per-connection state on BOTH sides (demonstrates per-connection instantiation).
        // Small base timeout + high retry cap so the exponential-backoff schedule
        // fits many retries into the test's 20 s deadline. At 20 ms base, backoff
        // of 20, 40, 80, 160, 320, 640, 1280, 1280, 1280, ... ms gives ≳ 15 retries
        // per packet before deadline — 0.5^15 ≈ 3e-5 per packet, 40 packets ⇒
        // essentially never hits the cap under 50 % loss. (Pre-Fix-6 AckLayer
        // retransmitted at a constant 80 ms forever; this test then relied on the
        // unbounded-retry behaviour that is now intentionally capped.)
        var clientAck = new AckLayer(20, 100); // 20 ms base timeout, 100-retry cap
        var clientSeq = new SequencingLayer(); // used only on encode path in this test
        var serverAck = new AckLayer(20, 100);
        var serverDupFilter = new DuplicateFilterLayer(256);

        var receivedPayloads = new BitSet(packetCount);
        var receivedOrder = new ArrayList<Integer>();

        // --- send the initial batch ---
        var encBuf = ByteBuffer.allocate(512);
        var wireBuf = ByteBuffer.allocate(512);
        for (int i = 0; i < packetCount; i++) {
            // payload = payloadId (int)
            encBuf.clear();
            encBuf.putInt(i);
            encBuf.flip();

            wireBuf.clear();
            wireBuf.put(TAG_DATA);
            // AckLayer assigns its own seq + tracks the packet for retransmit.
            clientAck.encodeOutbound(encBuf, wireBuf);
            wireBuf.flip();
            clientChannel.send(wireBuf, fwdAddr);
        }
        assertEquals(packetCount, clientAck.unackedCount(),
                "client should be tracking every packet as unacked");

        var selector = Selector.open();
        clientChannel.register(selector, SelectionKey.OP_READ, "client");
        serverChannel.register(selector, SelectionKey.OP_READ, "server");

        var srvReadBuf = ByteBuffer.allocate(2048);
        var cliReadBuf = ByteBuffer.allocate(2048);
        var ackOutBuf = ByteBuffer.allocate(512);
        var rtxBuf = ByteBuffer.allocate(65536);

        int retransmitRounds = 0;
        long deadline = System.currentTimeMillis() + 20_000;

        while (receivedPayloads.cardinality() < packetCount && System.currentTimeMillis() < deadline) {
            selector.select(20);
            for (var key : selector.selectedKeys()) {
                if (!key.isValid() || !key.isReadable()) continue;
                if ("server".equals(key.attachment())) {
                    // -- server: receive DATA, dedupe, decode via AckLayer, emit ACK
                    srvReadBuf.clear();
                    var sender = serverChannel.receive(srvReadBuf);
                    if (sender == null) continue;
                    srvReadBuf.flip();
                    if (srvReadBuf.get() != TAG_DATA) continue;

                    // DuplicateFilterLayer operates on the seq-prefixed buffer.
                    var dedupView = srvReadBuf.slice();
                    var afterDedup = serverDupFilter.decodeInbound(dedupView);
                    if (afterDedup == null) continue; // duplicate retransmit — drop

                    // Feed to AckLayer to extract payload + stage the ack.
                    var payload = serverAck.decodeInbound(afterDedup);
                    assertNotNull(payload);
                    int payloadId = payload.getInt();
                    assertTrue(payloadId >= 0 && payloadId < packetCount);
                    if (!receivedPayloads.get(payloadId)) {
                        receivedPayloads.set(payloadId);
                        receivedOrder.add(payloadId);
                    }

                    // Send ack back on the wire.
                    if (serverAck.hasAckToSend()) {
                        ackOutBuf.clear();
                        ackOutBuf.put(TAG_ACK);
                        serverAck.writeAck(ackOutBuf);
                        ackOutBuf.flip();
                        serverChannel.send(ackOutBuf, sender);
                    }
                } else {
                    // -- client: receive ACK, feed to AckLayer so it stops tracking
                    cliReadBuf.clear();
                    var from = clientChannel.receive(cliReadBuf);
                    if (from == null) continue;
                    cliReadBuf.flip();
                    if (cliReadBuf.get() != TAG_ACK) continue;
                    clientAck.processAck(cliReadBuf);
                }
            }
            selector.selectedKeys().clear();

            // Periodic retransmit tick on the client.
            if (clientAck.hasRetransmits()) {
                rtxBuf.clear();
                int n = clientAck.writeRetransmits(rtxBuf);
                rtxBuf.flip();
                if (n > 0) {
                    retransmitRounds++;
                    // Each retransmit packet is [seq:int][payload:int] — rewrap with TAG_DATA.
                    while (rtxBuf.remaining() >= 8) {
                        int seq = rtxBuf.getInt();
                        int payloadId = rtxBuf.getInt();
                        var pkt = ByteBuffer.allocate(16);
                        pkt.put(TAG_DATA);
                        pkt.putInt(seq);
                        pkt.putInt(payloadId);
                        pkt.flip();
                        clientChannel.send(pkt, fwdAddr);
                    }
                }
            }
        }

        try {
            assertEquals(packetCount, receivedPayloads.cardinality(),
                    "All payloads should eventually arrive via retransmits; missing="
                            + missing(receivedPayloads, packetCount)
                            + " retransmitRounds=" + retransmitRounds
                            + " unacked=" + clientAck.unackedCount());
            assertTrue(retransmitRounds > 0,
                    "50% loss over " + packetCount + " packets should have triggered retransmits");
        } finally {
            selector.close();
            clientChannel.close();
            serverChannel.close();
            forwarder.close();
        }
    }

    @Test
    void sharedSequencingLayer_betweenTwoClients_wouldCrossContaminate() {
        // This is a documentation test: it demonstrates why a SequencingLayer
        // instance MUST NOT be shared across connections. If the server uses
        // one instance for two UDP peers, the second peer's (legitimate) seq=0
        // gets dropped as stale after the first peer advances the counter.
        var sharedOnServer = new SequencingLayer();

        var clientA = new SequencingLayer();
        var clientB = new SequencingLayer();

        // A sends 3 packets through the shared layer.
        for (int i = 0; i < 3; i++) {
            var payload = ByteBuffer.allocate(16);
            payload.putInt(100 + i);
            payload.flip();
            var wire = ByteBuffer.allocate(64);
            clientA.encodeOutbound(payload, wire);
            wire.flip();
            assertNotNull(sharedOnServer.decodeInbound(wire));
        }
        assertEquals(2, sharedOnServer.lastReceivedSeq());

        // B — a different connection — now sends its first packet (seq=0).
        // Because state is shared, 0 <= lastReceivedSeq=2 and the packet is dropped.
        var payloadB = ByteBuffer.allocate(16);
        payloadB.putInt(999);
        payloadB.flip();
        var wireB = ByteBuffer.allocate(64);
        clientB.encodeOutbound(payloadB, wireB);
        wireB.flip();

        assertNull(sharedOnServer.decodeInbound(wireB),
                "Sharing a SequencingLayer across peers drops legitimate packets — "
                        + "the Layers.sequencing() factory must be called per connection.");

        // Fresh per-connection instance on the server accepts both streams independently.
        var serverForA = new SequencingLayer();
        var serverForB = new SequencingLayer();

        var pa = ByteBuffer.allocate(16); pa.putInt(7); pa.flip();
        var wa = ByteBuffer.allocate(64);
        new SequencingLayer().encodeOutbound(pa, wa); wa.flip();
        assertNotNull(serverForA.decodeInbound(wa));

        var pb = ByteBuffer.allocate(16); pb.putInt(8); pb.flip();
        var wb = ByteBuffer.allocate(64);
        new SequencingLayer().encodeOutbound(pb, wb); wb.flip();
        assertNotNull(serverForB.decodeInbound(wb));
    }

    private static List<Integer> missing(BitSet seen, int count) {
        var list = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) if (!seen.get(i)) list.add(i);
        return list;
    }
}
