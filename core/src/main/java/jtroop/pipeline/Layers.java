package jtroop.pipeline;

import jtroop.pipeline.layers.*;

import javax.crypto.SecretKey;

public final class Layers {
    private Layers() {}

    public static FramingLayer framing() { return new FramingLayer(); }
    public static CompressionLayer compression() { return new CompressionLayer(); }
    public static EncryptionLayer encryption(SecretKey key) { return new EncryptionLayer(key); }
    public static SequencingLayer sequencing() { return new SequencingLayer(); }
    public static DuplicateFilterLayer duplicateFilter() { return new DuplicateFilterLayer(1024); }
    public static DuplicateFilterLayer duplicateFilter(int capacity) { return new DuplicateFilterLayer(capacity); }
    public static AckLayer ack() { return new AckLayer(); }
    public static AckLayer ack(long retransmitTimeoutMs) { return new AckLayer(retransmitTimeoutMs); }
}
