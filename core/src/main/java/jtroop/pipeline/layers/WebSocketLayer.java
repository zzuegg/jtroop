package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Minimal RFC 6455 WebSocket codec — enough to round-trip short text frames
 * after an HTTP/1.1 upgrade.
 *
 * <h2>Wire format</h2>
 * A WebSocket frame consists of a 2-byte header, an optional 2- or 8-byte
 * extended length, an optional 4-byte mask, and the payload. The header
 * encodes FIN+RSV+opcode and MASK+length bits:
 *
 * <pre>
 *   byte 0: FIN(1) RSV(3) OPCODE(4)
 *   byte 1: MASK(1) LEN(7)
 *   [extended length]
 *   [4-byte mask if MASK=1]
 *   [payload]
 * </pre>
 *
 * <p>This layer is deliberately scoped for the
 * {@code HttpWebsocketUpgradeTest} — it handles single-fragment frames (FIN=1),
 * short payload lengths (0..125 + the 16-bit extended form 126), masked
 * inbound (per-client requirement) and unmasked outbound (server→client per
 * RFC 6455 §5.1). Production use would require: continuation frames, 64-bit
 * length, ping/pong/close handling, utf-8 validation, and optional
 * per-message-deflate. Not in scope here.
 *
 * <h2>Role</h2>
 * Construct with {@code role=SERVER} if you'll be unmasking inbound and
 * sending unmasked outbound (or mask-optional), or {@code role=CLIENT} if
 * you'll be masking outbound and accepting unmasked inbound. The HTTP→WS
 * upgrade test uses SERVER on the server side and CLIENT on the client side.
 *
 * <h2>Frame shape on the jtroop boundary</h2>
 * Inbound (decode): payload bytes only, passed up to the next layer.
 * Outbound (encode): caller passes payload bytes, this layer wraps them with
 * the WebSocket frame header and (client-side) masks them. Opcode is always
 * 0x1 (text) here — the test payload is UTF-8 text. Easy to extend to binary
 * (0x2) by threading an opcode field through the frame contract; out of
 * scope for the current test.
 */
public final class WebSocketLayer implements Layer {

    /** WebSocket handshake GUID from RFC 6455 §1.3. */
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public enum Role { SERVER, CLIENT }

    private static final int OPCODE_TEXT = 0x1;

    private final Role role;
    private final byte[] maskBytes = new byte[4];
    /** Buffer reused for unmasked payloads on decode. */
    private final ByteBuffer decoded = ByteBuffer.allocate(65536);
    /** Counter seeds the client-side mask so two concurrent encodes don't collide. */
    private int maskCounter = 0xDEADBEEF;

    public WebSocketLayer(Role role) {
        this.role = role;
    }

    /** Convenience: server-role layer. */
    public WebSocketLayer() { this(Role.SERVER); }

    /**
     * Compute the {@code Sec-WebSocket-Accept} response header value from the
     * client's {@code Sec-WebSocket-Key}. Per RFC 6455 §4.2.2:
     * <pre>base64(sha1(key + GUID))</pre>
     */
    public static String computeAcceptKey(String clientKey) {
        try {
            var sha1 = MessageDigest.getInstance("SHA-1");
            var input = (clientKey + GUID).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var digest = sha1.digest(input);
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        int start = wire.position();
        int limit = wire.limit();
        if (limit - start < 2) return null;

        int b0 = wire.get(start) & 0xFF;
        int b1 = wire.get(start + 1) & 0xFF;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        int len7 = b1 & 0x7F;

        int headerLen = 2;
        long payloadLen;
        if (len7 < 126) {
            payloadLen = len7;
        } else if (len7 == 126) {
            if (limit - start < 4) return null;
            payloadLen = ((wire.get(start + 2) & 0xFF) << 8) | (wire.get(start + 3) & 0xFF);
            headerLen = 4;
        } else {
            // 127: 64-bit length — not supported in this minimal codec.
            throw new IllegalStateException("WebSocket 64-bit length not supported");
        }

        int maskStart = start + headerLen;
        int payloadStart = masked ? maskStart + 4 : maskStart;
        long totalLen = (long) headerLen + (masked ? 4 : 0) + payloadLen;
        if (limit - start < totalLen) return null;

        // Per RFC 6455 §5.1, server MUST reject unmasked client→server frames.
        if (role == Role.SERVER && !masked) {
            throw new IllegalStateException("Expected masked WebSocket frame from client");
        }
        // For the minimal test we only need text frames and treat non-fin /
        // non-text as errors. Ping/Pong (9/10) and Close (8) would go here in
        // a full implementation.
        if (!fin || opcode != OPCODE_TEXT) {
            throw new IllegalStateException("Unsupported WebSocket frame: fin=" + fin + " opcode=" + opcode);
        }

        decoded.clear();
        if (masked) {
            maskBytes[0] = wire.get(maskStart);
            maskBytes[1] = wire.get(maskStart + 1);
            maskBytes[2] = wire.get(maskStart + 2);
            maskBytes[3] = wire.get(maskStart + 3);
            for (int i = 0; i < (int) payloadLen; i++) {
                decoded.put((byte) (wire.get(payloadStart + i) ^ maskBytes[i & 3]));
            }
        } else {
            for (int i = 0; i < (int) payloadLen; i++) {
                decoded.put(wire.get(payloadStart + i));
            }
        }
        decoded.flip();
        wire.position((int) (start + totalLen));
        return decoded;
    }

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        int len = payload.remaining();
        // FIN + text opcode
        out.put((byte) (0x80 | OPCODE_TEXT));
        boolean mask = role == Role.CLIENT;
        int maskBit = mask ? 0x80 : 0x00;
        if (len < 126) {
            out.put((byte) (maskBit | len));
        } else if (len <= 0xFFFF) {
            out.put((byte) (maskBit | 126));
            out.put((byte) ((len >> 8) & 0xFF));
            out.put((byte) (len & 0xFF));
        } else {
            throw new IllegalStateException(
                    "Payload too large for this minimal WebSocket layer (len=" + len + ")");
        }
        if (mask) {
            int seed = ++maskCounter;
            maskBytes[0] = (byte) seed;
            maskBytes[1] = (byte) (seed >>> 8);
            maskBytes[2] = (byte) (seed >>> 16);
            maskBytes[3] = (byte) (seed >>> 24);
            out.put(maskBytes, 0, 4);
            int pos = payload.position();
            for (int i = 0; i < len; i++) {
                out.put((byte) (payload.get(pos + i) ^ maskBytes[i & 3]));
            }
            payload.position(pos + len);
        } else {
            out.put(payload);
        }
    }
}
