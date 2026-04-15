package net.pipeline;

import net.pipeline.layers.CompressionLayer;
import net.pipeline.layers.EncryptionLayer;
import net.pipeline.layers.FramingLayer;

import javax.crypto.SecretKey;

public final class Layers {
    private Layers() {}

    public static FramingLayer framing() { return new FramingLayer(); }
    public static CompressionLayer compression() { return new CompressionLayer(); }
    public static EncryptionLayer encryption(SecretKey key) { return new EncryptionLayer(key); }
}
