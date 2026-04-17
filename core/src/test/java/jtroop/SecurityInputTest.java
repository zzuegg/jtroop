package jtroop;

import jtroop.codec.CodecRegistry;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.layers.EncryptionLayer;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.pipeline.layers.HttpLayer;
import jtroop.session.ConnectionId;
import jtroop.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security-focused tests for the top-5 most dangerous input vectors.
 *
 * 1. SessionStore: stale/out-of-range ConnectionId must not corrupt other sessions
 * 2. Codec: garbled payload causing BufferUnderflowException (safe crash, no corruption)
 * 3. Encryption: tampered ciphertext must fail tag verification before exposing plaintext
 * 4. HTTP: Content-Length smuggling (CL larger than actual body stalls; CL mismatch)
 * 5. Framing + Codec: valid frame with unregistered type ID
 */
@Timeout(10)
class SecurityInputTest {

    // ================================================================
    // 1. SessionStore — out-of-range and stale ConnectionId
    // ================================================================

    @Test
    void sessionStore_outOfRangeIndex_doesNotCrash() {
        var store = new SessionStore(16);
        var bogus = ConnectionId.of(9999, 1); // index way out of range
        // These must not throw ArrayIndexOutOfBoundsException
        assertFalse(store.isActive(bogus));
        store.setState(bogus, 42);       // should be a no-op
        assertEquals(0, store.getState(bogus));
        store.setLastActivity(bogus, 12345L);
        assertEquals(0L, store.getLastActivity(bogus));
    }

    @Test
    void sessionStore_negativeIndex_doesNotCrash() {
        var store = new SessionStore(16);
        var bogus = ConnectionId.of(-1, 1);
        assertFalse(store.isActive(bogus));
        store.setState(bogus, 42);
        assertEquals(0, store.getState(bogus));
        store.setLastActivity(bogus, 12345L);
        assertEquals(0L, store.getLastActivity(bogus));
    }

    @Test
    void sessionStore_staleGeneration_doesNotCorruptActiveSession() {
        var store = new SessionStore(16);
        var id1 = store.allocate();
        store.setState(id1, 100);
        assertEquals(100, store.getState(id1));

        // Release and re-allocate the same slot
        store.release(id1);
        var id2 = store.allocate();
        // id2 should have same index but different generation
        assertEquals(id1.index(), id2.index());
        assertNotEquals(id1.generation(), id2.generation());

        store.setState(id2, 200);

        // Stale handle must not overwrite the new session's state
        store.setState(id1, 999);
        assertEquals(200, store.getState(id2));  // id2's state unchanged
        assertEquals(0, store.getState(id1));    // stale handle returns 0
    }

    // ================================================================
    // 2. Codec — garbled payload bytes with valid framing
    // ================================================================

    record SimpleMsg(int x, float y) {}

    @Test
    void codec_truncatedPayload_throwsNotCorrupts() {
        var codec = new CodecRegistry();
        codec.register(SimpleMsg.class);

        // Encode a valid message to learn the type ID
        var encodeBuf = ByteBuffer.allocate(256);
        codec.encode(new SimpleMsg(1, 2.0f), encodeBuf);
        encodeBuf.flip();
        int typeId = encodeBuf.getShort(0) & 0xFFFF;

        // Now craft a buffer with valid type ID but truncated body (only 2 bytes
        // instead of 8 needed for int + float)
        var bad = ByteBuffer.allocate(4);
        bad.putShort((short) typeId);
        bad.putShort((short) 0); // only 2 bytes of payload
        bad.flip();

        // Must throw (BufferUnderflowException) rather than return garbage
        assertThrows(Exception.class, () -> codec.decode(new ReadBuffer(bad)));
    }

    @Test
    void codec_unregisteredTypeId_throws() {
        var codec = new CodecRegistry();
        codec.register(SimpleMsg.class);

        // Type ID 0xFFFF is almost certainly unregistered
        var bad = ByteBuffer.allocate(10);
        bad.putShort((short) 0xFFFF);
        bad.putInt(0);
        bad.putInt(0);
        bad.flip();

        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new ReadBuffer(bad)));
    }

    // ================================================================
    // 3. Encryption — tampered ciphertext must fail decryption
    // ================================================================

    @Test
    void encryption_tamperedCiphertext_throwsOnDecrypt() throws Exception {
        var key = KeyGenerator.getInstance("AES").generateKey();
        var layer = new EncryptionLayer(key);

        // Encrypt a message
        var payload = ByteBuffer.allocate(64);
        payload.put("hello world".getBytes(StandardCharsets.UTF_8));
        payload.flip();

        var wireBuf = ByteBuffer.allocate(256);
        layer.encodeOutbound(payload, wireBuf);
        wireBuf.flip();

        // Tamper with a ciphertext byte (after IV + length prefix = 16 bytes)
        int tamperPos = 16 + 2; // somewhere in the ciphertext
        if (tamperPos < wireBuf.limit()) {
            byte original = wireBuf.get(tamperPos);
            wireBuf.put(tamperPos, (byte) (original ^ 0xFF));
        }

        // Decryption must throw (AEADBadTagException wrapped in RuntimeException)
        var toDecrypt = wireBuf;
        assertThrows(RuntimeException.class,
                () -> layer.decodeInbound(toDecrypt));
    }

    @Test
    void encryption_truncatedFrame_returnsNull() throws Exception {
        var key = KeyGenerator.getInstance("AES").generateKey();
        var layer = new EncryptionLayer(key);

        // Only send IV + length prefix but no ciphertext
        var wire = ByteBuffer.allocate(16);
        wire.put(new byte[12]); // fake IV
        wire.putInt(100);       // claims 100 bytes of ciphertext
        wire.flip();

        // Not enough data -- should return null (partial frame)
        assertNull(layer.decodeInbound(wire));
    }

    // ================================================================
    // 4. HTTP — Content-Length smuggling: CL > actual body
    // ================================================================

    @Test
    void http_contentLengthLargerThanBody_stallsDoesNotProcess() {
        var layer = new HttpLayer();
        // Content-Length says 1000 but only 5 bytes of body present
        var req = ("POST /x HTTP/1.1\r\n"
                + "Host: h\r\n"
                + "Content-Length: 1000\r\n"
                + "\r\nhello").getBytes(StandardCharsets.UTF_8);
        // Should return null (incomplete body), NOT process partial data
        var result = layer.decodeInbound(ByteBuffer.wrap(req));
        assertNull(result, "Must wait for full Content-Length body, not process partial");
    }

    @Test
    void http_nullBytesInHeaders_doNotCorruptParsing() {
        var layer = new HttpLayer();
        // Null byte in a header value -- should not cause crash
        var req = "GET /x HTTP/1.1\r\nHost: h\u0000ost\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        // Should parse without crashing (null byte is just data)
        var frame = layer.decodeInbound(ByteBuffer.wrap(req));
        assertNotNull(frame, "Null bytes in headers should not crash the parser");
    }

    @Test
    void http_transferEncodingChunked_rejected() {
        var layer = new HttpLayer();
        var req = ("POST /x HTTP/1.1\r\n"
                + "Host: h\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n5\r\nhello\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var ex = assertThrows(HttpLayer.HttpProtocolException.class,
                () -> layer.decodeInbound(ByteBuffer.wrap(req)));
        assertEquals(501, ex.status());
    }

    // ================================================================
    // 5. Framing + Codec end-to-end: valid frame, garbled record bytes
    // ================================================================

    @Test
    void framingThenCodec_garbledRecordBytes_throwsSafely() {
        var framing = new FramingLayer();
        var codec = new CodecRegistry();
        codec.register(SimpleMsg.class);

        // Build a valid frame containing random garbage bytes
        var payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte)0x9A, (byte)0xBC};
        var wire = ByteBuffer.allocate(4 + payload.length);
        wire.putInt(payload.length);
        wire.put(payload);
        wire.flip();

        // FramingLayer should accept the frame (valid length)
        var frame = framing.decodeInbound(wire);
        assertNotNull(frame);

        // Codec decode must throw (unknown type id or buffer underflow)
        assertThrows(Exception.class, () -> codec.decode(new ReadBuffer(frame)));
    }
}
