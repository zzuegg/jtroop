package jtroop.pipeline;

import jtroop.pipeline.layers.AckLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class AckLayerTest {

    @Test
    void encodeOutbound_addsSequenceNumber() {
        var layer = new AckLayer();
        var payload = ByteBuffer.allocate(16);
        payload.putInt(42);
        payload.flip();

        var out = ByteBuffer.allocate(64);
        layer.encodeOutbound(payload, out);
        out.flip();

        // First 4 bytes = sequence number
        int seq = out.getInt();
        assertEquals(0, seq);
        assertEquals(42, out.getInt());
    }

    @Test
    void decodeInbound_returnsPayload() {
        var sender = new AckLayer();
        var receiver = new AckLayer();

        var payload = ByteBuffer.allocate(16);
        payload.putInt(42);
        payload.flip();

        var wire = ByteBuffer.allocate(64);
        sender.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = receiver.decodeInbound(wire);
        assertNotNull(decoded);
        assertEquals(42, decoded.getInt());
    }

    @Test
    void decodeInbound_generatesAck() {
        var sender = new AckLayer();
        var receiver = new AckLayer();

        var payload = ByteBuffer.allocate(16);
        payload.putInt(42);
        payload.flip();

        var wire = ByteBuffer.allocate(64);
        sender.encodeOutbound(payload, wire);
        wire.flip();

        receiver.decodeInbound(wire);

        // Receiver should have an ack pending
        assertTrue(receiver.hasAckToSend());
        var ackBuf = ByteBuffer.allocate(16);
        receiver.writeAck(ackBuf);
        ackBuf.flip();
        assertEquals(0, ackBuf.getInt()); // acks sequence 0
    }

    @Test
    void sender_tracksUnacked() {
        var layer = new AckLayer();

        var p1 = ByteBuffer.allocate(8);
        p1.putInt(1);
        p1.flip();
        var out1 = ByteBuffer.allocate(64);
        layer.encodeOutbound(p1, out1);

        assertEquals(1, layer.unackedCount());
    }

    @Test
    void sender_processesAck_removesFromUnacked() {
        var layer = new AckLayer();

        var p = ByteBuffer.allocate(8);
        p.putInt(1);
        p.flip();
        var out = ByteBuffer.allocate(64);
        layer.encodeOutbound(p, out);
        assertEquals(1, layer.unackedCount());

        // Simulate receiving an ack for seq 0
        var ackBuf = ByteBuffer.allocate(4);
        ackBuf.putInt(0);
        ackBuf.flip();
        layer.processAck(ackBuf);

        assertEquals(0, layer.unackedCount());
    }

    @Test
    void sender_capsRetransmits_atMaxRetries_andReleasesSlot() throws InterruptedException {
        // 1ms base timeout, cap at 3 retries → exponential backoff: 1, 2, 4ms then give up.
        var layer = new AckLayer(1, 3);

        var p = ByteBuffer.allocate(8);
        p.putInt(1);
        p.flip();
        var out = ByteBuffer.allocate(64);
        layer.encodeOutbound(p, out);
        assertEquals(1, layer.unackedCount());

        // Silent peer: poll retransmits in a loop and count. Current main
        // retransmits forever (bounded only by our 2s safety deadline).
        var buf = ByteBuffer.allocate(1024);
        int totalRetransmits = 0;
        long deadline = System.nanoTime() + 2_000_000_000L; // 2s safety cap
        while (System.nanoTime() < deadline) {
            Thread.sleep(40);
            buf.clear();
            totalRetransmits += layer.writeRetransmits(buf);
            if (layer.unackedCount() == 0) break;
        }

        assertEquals(3, totalRetransmits,
                "should retransmit exactly maxRetries times; got " + totalRetransmits);
        assertEquals(1, layer.exhaustedCount(),
                "exhausted packet count must increment when cap is reached");
        assertEquals(0, layer.unackedCount(),
                "slot must be released once retries are exhausted");
    }

    @Test
    void sender_exhaustedCount_isZero_whenAcksArriveInTime() throws InterruptedException {
        var layer = new AckLayer(1, 3);
        var p = ByteBuffer.allocate(8);
        p.putInt(1);
        p.flip();
        var out = ByteBuffer.allocate(64);
        layer.encodeOutbound(p, out);

        // Ack arrives before any retransmit cycle.
        var ackBuf = ByteBuffer.allocate(4);
        ackBuf.putInt(0);
        ackBuf.flip();
        layer.processAck(ackBuf);

        // Wait past the retransmit window; nothing should be retransmitted or exhausted.
        Thread.sleep(50);
        assertEquals(0, layer.exhaustedCount());
        assertEquals(0, layer.unackedCount());
        var buf = ByteBuffer.allocate(64);
        assertEquals(0, layer.writeRetransmits(buf));
    }

    @Test
    void sender_retransmitsUnacked() {
        var layer = new AckLayer(50); // 50ms timeout

        var p = ByteBuffer.allocate(8);
        p.putInt(1);
        p.flip();
        var out = ByteBuffer.allocate(64);
        layer.encodeOutbound(p, out);

        // Before timeout: no retransmit
        assertFalse(layer.hasRetransmits());

        // Simulate time passing
        try { Thread.sleep(60); } catch (InterruptedException _) {}

        // After timeout: should have retransmit
        assertTrue(layer.hasRetransmits());

        var retransmit = ByteBuffer.allocate(64);
        int count = layer.writeRetransmits(retransmit);
        assertEquals(1, count);
        retransmit.flip();

        // Retransmit contains: seq(4) + original payload
        int seq = retransmit.getInt();
        assertEquals(0, seq);
        assertEquals(1, retransmit.getInt()); // original payload
    }
}
