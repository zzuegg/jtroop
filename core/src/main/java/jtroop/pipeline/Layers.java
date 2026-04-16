package jtroop.pipeline;

import jtroop.pipeline.layers.*;

import javax.crypto.SecretKey;

/**
 * Factory helpers for {@link Layer} implementations.
 *
 * <p><b>Stateful vs stateless layers.</b> {@link FramingLayer} and
 * {@link CompressionLayer} are effectively stateless — one instance can be
 * shared by multiple connections. In contrast {@link SequencingLayer},
 * {@link DuplicateFilterLayer}, {@link AckLayer} and {@link EncryptionLayer}
 * own per-connection state (sequence counters, anti-replay windows, cipher
 * nonces, unacked maps). A fresh instance MUST be constructed per connection
 * for those — sharing them corrupts the protocol.
 */
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
    public static HttpLayer http() { return new HttpLayer(); }
}
