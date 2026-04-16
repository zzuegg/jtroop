package jtroop.pipeline;

import jtroop.pipeline.layers.CompressionLayer;
import jtroop.pipeline.layers.EncryptionLayer;
import jtroop.pipeline.layers.FramingLayer;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

class CompressionEncryptionLayerTest {

    @Test
    void compressionLayer_roundtrip() {
        var layer = new CompressionLayer();
        var payload = ByteBuffer.allocate(256);
        // repetitive data compresses well
        for (int i = 0; i < 20; i++) payload.putInt(42);
        payload.flip();
        int originalSize = payload.remaining();
        var original = new byte[originalSize];
        payload.get(original);
        payload.flip();

        var wire = ByteBuffer.allocate(512);
        layer.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = layer.decodeInbound(wire);
        assertNotNull(decoded);
        var result = new byte[decoded.remaining()];
        decoded.get(result);
        assertArrayEquals(original, result);
    }

    @Test
    void compressionLayer_compressesData() {
        var layer = new CompressionLayer();
        var payload = ByteBuffer.allocate(256);
        for (int i = 0; i < 50; i++) payload.putInt(42);
        payload.flip();
        int originalSize = payload.remaining();

        var wire = ByteBuffer.allocate(512);
        layer.encodeOutbound(payload, wire);
        wire.flip();
        int compressedSize = wire.remaining();

        assertTrue(compressedSize < originalSize, "Compressed should be smaller than original");
    }

    @Test
    void encryptionLayer_roundtrip() throws Exception {
        var key = generateKey();
        var encLayer = new EncryptionLayer(key);
        var decLayer = new EncryptionLayer(key);

        var payload = ByteBuffer.allocate(64);
        payload.putInt(42);
        payload.putLong(123456789L);
        payload.flip();
        var original = new byte[payload.remaining()];
        payload.get(original);
        payload.flip();

        var wire = ByteBuffer.allocate(256);
        encLayer.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = decLayer.decodeInbound(wire);
        assertNotNull(decoded);
        var result = new byte[decoded.remaining()];
        decoded.get(result);
        assertArrayEquals(original, result);
    }

    @Test
    void encryptionLayer_variousPayloadSizes() throws Exception {
        var key = generateKey();
        var encLayer = new EncryptionLayer(key);
        var decLayer = new EncryptionLayer(key);
        int[] sizes = {0, 1, 16, 1024, 4096, 16384, 65000};
        for (int size : sizes) {
            var plain = new byte[size];
            for (int i = 0; i < size; i++) plain[i] = (byte) (i * 31);
            var payload = ByteBuffer.allocate(Math.max(size, 1));
            payload.put(plain);
            payload.flip();

            var wire = ByteBuffer.allocate(size + 256);
            encLayer.encodeOutbound(payload, wire);
            wire.flip();

            var decoded = decLayer.decodeInbound(wire);
            assertNotNull(decoded, "decoded null for size " + size);
            var result = new byte[decoded.remaining()];
            decoded.get(result);
            assertArrayEquals(plain, result, "roundtrip mismatch for size " + size);
        }
    }

    @Test
    void encryptionLayer_tamperedCiphertextRejected() throws Exception {
        var key = generateKey();
        var encLayer = new EncryptionLayer(key);
        var decLayer = new EncryptionLayer(key);

        var payload = ByteBuffer.allocate(64);
        payload.putLong(0xDEADBEEFCAFEBABEL);
        payload.flip();

        var wire = ByteBuffer.allocate(256);
        encLayer.encodeOutbound(payload, wire);
        wire.flip();

        // Flip one byte deep inside the ciphertext (past IV + length prefix).
        int tamperIdx = wire.position() + 12 + 4 + 2;
        wire.put(tamperIdx, (byte) (wire.get(tamperIdx) ^ 0x01));

        assertThrows(RuntimeException.class, () -> decLayer.decodeInbound(wire),
                "MAC must detect tampered ciphertext");
    }

    @Test
    void encryptionLayer_uniqueIvPerEncode() throws Exception {
        // Guardrail against the catastrophic GCM failure mode of IV reuse.
        var key = generateKey();
        var layer = new EncryptionLayer(key);
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            var payload = ByteBuffer.allocate(8);
            payload.putLong(i);
            payload.flip();
            var wire = ByteBuffer.allocate(128);
            layer.encodeOutbound(payload, wire);
            wire.flip();
            var iv = new byte[12];
            wire.get(iv);
            var hex = java.util.HexFormat.of().formatHex(iv);
            assertTrue(seen.add(hex), "IV reused at iteration " + i);
        }
    }

    @Test
    void encryptionLayer_ciphertextDiffersFromPlaintext() throws Exception {
        var key = generateKey();
        var layer = new EncryptionLayer(key);

        var payload = ByteBuffer.allocate(64);
        payload.putInt(42);
        payload.putInt(99);
        payload.flip();
        var plainBytes = new byte[payload.remaining()];
        payload.get(plainBytes);
        payload.flip();

        var wire = ByteBuffer.allocate(256);
        layer.encodeOutbound(payload, wire);
        wire.flip();
        var cipherBytes = new byte[wire.remaining()];
        wire.get(cipherBytes);

        assertFalse(java.util.Arrays.equals(plainBytes, cipherBytes));
    }

    @Test
    void pipeline_framing_compression_roundtrip() {
        var pipeline = new Pipeline(new CompressionLayer(), new FramingLayer());

        var payload = ByteBuffer.allocate(256);
        for (int i = 0; i < 20; i++) payload.putInt(i);
        payload.flip();
        var original = new byte[payload.remaining()];
        payload.get(original);
        payload.flip();

        var wire = ByteBuffer.allocate(512);
        pipeline.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = pipeline.decodeInbound(wire);
        assertNotNull(decoded);
        var result = new byte[decoded.remaining()];
        decoded.get(result);
        assertArrayEquals(original, result);
    }

    @Test
    void pipeline_framing_encryption_compression_roundtrip() throws Exception {
        var key = generateKey();
        var encodePipeline = new Pipeline(
                new CompressionLayer(), new EncryptionLayer(key), new FramingLayer());
        var decodePipeline = new Pipeline(
                new CompressionLayer(), new EncryptionLayer(key), new FramingLayer());

        var payload = ByteBuffer.allocate(256);
        for (int i = 0; i < 10; i++) payload.putInt(i);
        payload.flip();
        var original = new byte[payload.remaining()];
        payload.get(original);
        payload.flip();

        var wire = ByteBuffer.allocate(1024);
        encodePipeline.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = decodePipeline.decodeInbound(wire);
        assertNotNull(decoded);
        var result = new byte[decoded.remaining()];
        decoded.get(result);
        assertArrayEquals(original, result);
    }

    private SecretKey generateKey() throws NoSuchAlgorithmException {
        var gen = KeyGenerator.getInstance("AES");
        gen.init(128);
        return gen.generateKey();
    }
}
