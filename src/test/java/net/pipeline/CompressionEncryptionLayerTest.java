package net.pipeline;

import net.pipeline.layers.CompressionLayer;
import net.pipeline.layers.EncryptionLayer;
import net.pipeline.layers.FramingLayer;
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
