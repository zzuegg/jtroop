package jtroop.pipeline;

import java.nio.ByteBuffer;

public interface Layer {
    void encodeOutbound(ByteBuffer payload, ByteBuffer out);
    ByteBuffer decodeInbound(ByteBuffer wire);
}
