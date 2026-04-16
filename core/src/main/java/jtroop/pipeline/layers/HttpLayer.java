package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 layer — parses incoming HTTP requests into intermediate binary frames
 * and emits outbound HTTP responses from response frames.
 *
 * Frame formats:
 *   Request:  [method_len(u16)][method][path_len(u16)][path][body_len(u32)][body]
 *   Response: [status(u16)][reason_len(u16)][reason][ctype_len(u16)][ctype][body_len(u32)][body]
 *
 * Zero-allocation parse path: reads directly from/to ByteBuffers using
 * position+length indices. No String/byte[] allocation in the hot path.
 */
public final class HttpLayer implements Layer {

    public record ParsedRequest(String method, String path, byte[] body) {}

    // Pre-computed byte patterns
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};
    private static final byte[] CONTENT_LENGTH = "content-length:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTTP_200_OK_HEADER =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEEPALIVE_CRLF =
            "\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    // Reusable per-instance parse buffer for the frame output
    private final ByteBuffer frameBuf = ByteBuffer.allocate(65536);

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        int start = wire.position();
        int headerEnd = indexOf(wire, CRLF_CRLF);
        if (headerEnd < 0) return null;

        // Parse request line directly from bytes (no String allocation)
        // Find space after method
        int methodEnd = indexOfByte(wire, start, headerEnd, (byte) ' ');
        if (methodEnd < 0) return null;
        // Find space after path
        int pathEnd = indexOfByte(wire, methodEnd + 1, headerEnd, (byte) ' ');
        if (pathEnd < 0) return null;

        // Find Content-Length in headers (case-insensitive byte scan)
        int contentLength = findContentLength(wire, pathEnd, headerEnd);

        // Check body is complete
        int bodyStart = headerEnd + 4;
        if (wire.limit() - bodyStart < contentLength) return null;

        // Build frame into pre-allocated buffer (zero allocation)
        frameBuf.clear();
        int methodLen = methodEnd - start;
        int pathLen = pathEnd - methodEnd - 1;
        frameBuf.putShort((short) methodLen);
        // Copy method bytes
        for (int i = 0; i < methodLen; i++) frameBuf.put(wire.get(start + i));
        frameBuf.putShort((short) pathLen);
        for (int i = 0; i < pathLen; i++) frameBuf.put(wire.get(methodEnd + 1 + i));
        frameBuf.putInt(contentLength);
        for (int i = 0; i < contentLength; i++) frameBuf.put(wire.get(bodyStart + i));

        wire.position(bodyStart + contentLength);
        frameBuf.flip();
        return frameBuf;
    }

    @Override
    public void encodeOutbound(ByteBuffer frame, ByteBuffer out) {
        int status = frame.getShort() & 0xFFFF;
        int reasonLen = frame.getShort() & 0xFFFF;
        int reasonStart = frame.position();
        frame.position(reasonStart + reasonLen);
        int ctypeLen = frame.getShort() & 0xFFFF;
        int ctypeStart = frame.position();
        frame.position(ctypeStart + ctypeLen);
        int bodyLen = frame.getInt();
        int bodyStart = frame.position();

        // Fast path: 200 OK text/plain — common case for benchmarks
        if (status == 200 && reasonLen == 2
                && frame.get(reasonStart) == 'O' && frame.get(reasonStart + 1) == 'K'
                && isTextPlain(frame, ctypeStart, ctypeLen)) {
            out.put(HTTP_200_OK_HEADER);
            writeIntAsDecimal(out, bodyLen);
            out.put(KEEPALIVE_CRLF);
            for (int i = 0; i < bodyLen; i++) out.put(frame.get(bodyStart + i));
            return;
        }

        // Slow path: general response
        writeBytes(out, "HTTP/1.1 ");
        writeIntAsDecimal(out, status);
        out.put((byte) ' ');
        for (int i = 0; i < reasonLen; i++) out.put(frame.get(reasonStart + i));
        writeBytes(out, "\r\nContent-Type: ");
        for (int i = 0; i < ctypeLen; i++) out.put(frame.get(ctypeStart + i));
        writeBytes(out, "\r\nContent-Length: ");
        writeIntAsDecimal(out, bodyLen);
        out.put(KEEPALIVE_CRLF);
        for (int i = 0; i < bodyLen; i++) out.put(frame.get(bodyStart + i));
    }

    private static boolean isTextPlain(ByteBuffer buf, int start, int len) {
        if (len != 10) return false;
        return buf.get(start) == 't' && buf.get(start + 1) == 'e'
                && buf.get(start + 2) == 'x' && buf.get(start + 3) == 't'
                && buf.get(start + 4) == '/' && buf.get(start + 5) == 'p'
                && buf.get(start + 6) == 'l' && buf.get(start + 7) == 'a'
                && buf.get(start + 8) == 'i' && buf.get(start + 9) == 'n';
    }

    private static void writeIntAsDecimal(ByteBuffer out, int value) {
        if (value == 0) { out.put((byte) '0'); return; }
        // Write digits in reverse order, then reverse
        int start = out.position();
        while (value > 0) {
            out.put((byte) ('0' + value % 10));
            value /= 10;
        }
        // Reverse the digits
        int end = out.position() - 1;
        while (start < end) {
            byte tmp = out.get(start);
            out.put(start, out.get(end));
            out.put(end, tmp);
            start++; end--;
        }
    }

    private static void writeBytes(ByteBuffer out, String s) {
        for (int i = 0; i < s.length(); i++) out.put((byte) s.charAt(i));
    }

    private static int findContentLength(ByteBuffer wire, int from, int to) {
        // Case-insensitive search for "content-length:"
        int patLen = CONTENT_LENGTH.length;
        outer:
        for (int i = from; i <= to - patLen; i++) {
            for (int j = 0; j < patLen; j++) {
                byte b = wire.get(i + j);
                // Lowercase comparison (ASCII)
                if ((b | 0x20) != CONTENT_LENGTH[j]) continue outer;
            }
            // Found — parse number
            int numStart = i + patLen;
            int value = 0;
            // Skip spaces
            while (numStart < to && wire.get(numStart) == ' ') numStart++;
            while (numStart < to) {
                byte b = wire.get(numStart);
                if (b < '0' || b > '9') break;
                value = value * 10 + (b - '0');
                numStart++;
            }
            return value;
        }
        return 0;
    }

    private static int indexOfByte(ByteBuffer buf, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (buf.get(i) == target) return i;
        }
        return -1;
    }

    private static int indexOf(ByteBuffer buf, byte[] pattern) {
        int limit = buf.limit() - pattern.length + 1;
        outer:
        for (int i = buf.position(); i < limit; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buf.get(i + j) != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // --- Helpers for building/parsing frames (used by tests and examples) ---

    public static ByteBuffer buildRequestFrame(String method, String path, byte[] body) {
        var methodBytes = method.getBytes(StandardCharsets.UTF_8);
        var pathBytes = path.getBytes(StandardCharsets.UTF_8);
        var buf = ByteBuffer.allocate(8 + methodBytes.length + pathBytes.length + body.length);
        buf.putShort((short) methodBytes.length);
        buf.put(methodBytes);
        buf.putShort((short) pathBytes.length);
        buf.put(pathBytes);
        buf.putInt(body.length);
        buf.put(body);
        buf.flip();
        return buf;
    }

    public static ParsedRequest parseFrame(ByteBuffer frame) {
        int methodLen = frame.getShort() & 0xFFFF;
        var methodBytes = new byte[methodLen];
        frame.get(methodBytes);
        int pathLen = frame.getShort() & 0xFFFF;
        var pathBytes = new byte[pathLen];
        frame.get(pathBytes);
        int bodyLen = frame.getInt();
        var body = new byte[bodyLen];
        frame.get(body);
        return new ParsedRequest(
                new String(methodBytes, StandardCharsets.UTF_8),
                new String(pathBytes, StandardCharsets.UTF_8),
                body);
    }

    public static ByteBuffer buildResponseFrame(int status, String reason, String contentType, byte[] body) {
        var reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        var ctypeBytes = contentType.getBytes(StandardCharsets.UTF_8);
        var buf = ByteBuffer.allocate(10 + reasonBytes.length + ctypeBytes.length + body.length);
        buf.putShort((short) status);
        buf.putShort((short) reasonBytes.length);
        buf.put(reasonBytes);
        buf.putShort((short) ctypeBytes.length);
        buf.put(ctypeBytes);
        buf.putInt(body.length);
        buf.put(body);
        buf.flip();
        return buf;
    }
}
