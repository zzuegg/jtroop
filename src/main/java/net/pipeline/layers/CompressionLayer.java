package net.pipeline.layers;

import net.pipeline.Layer;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class CompressionLayer implements Layer {

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        var input = new byte[payload.remaining()];
        payload.get(input);

        var deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();

        var compressed = new byte[input.length + 64];
        int compressedLen = deflater.deflate(compressed);
        deflater.end();

        out.putInt(input.length); // original size for inflate
        out.put(compressed, 0, compressedLen);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int originalSize = wire.getInt();
        var compressed = new byte[wire.remaining()];
        wire.get(compressed);

        var inflater = new Inflater();
        inflater.setInput(compressed);
        var output = new byte[originalSize];
        try {
            inflater.inflate(output);
        } catch (java.util.zip.DataFormatException e) {
            throw new RuntimeException("Decompression failed", e);
        }
        inflater.end();
        return ByteBuffer.wrap(output);
    }
}
