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
 *
 * On protocol errors (malformed request, unsupported feature) the decoder throws
 * {@link HttpProtocolException} carrying the HTTP status code the caller should
 * return. The layer resets internal state so that the subsequent error response
 * is emitted with {@code Connection: close} — we cannot safely reuse a connection
 * after a framing error.
 */
public final class HttpLayer implements Layer {

    /** Upper bound on the header section (request line + all headers). */
    public static final int MAX_HEADER_SIZE = 8192;
    /** Upper bound on the request body. */
    public static final int MAX_BODY_SIZE = 1024 * 1024;

    public record ParsedRequest(String method, String path, byte[] body) {}

    /**
     * Thrown when the wire bytes describe a request this layer refuses to process
     * (malformed request line, oversized URI/headers, unsupported HTTP version,
     * Transfer-Encoding: chunked, missing Host on HTTP/1.1, missing/duplicate
     * Content-Length on a body-bearing method). The {@link #status()} value is
     * the HTTP status code the caller should emit before closing the connection.
     */
    public static final class HttpProtocolException extends RuntimeException {
        private final int status;
        public HttpProtocolException(int status, String msg) { super(msg); this.status = status; }
        public int status() { return status; }
    }

    // Pre-computed byte patterns. Header-name patterns are lowercase ASCII; the
    // scan folds incoming bytes with (b | 0x20) only for letters, so '-' (0x2D)
    // and ':' (0x3A) compare exactly.
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};
    private static final byte[] CONTENT_LENGTH    = "content-length:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_ENCODING = "transfer-encoding:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HOST_HEADER       = "host:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_HEADER = "connection:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTTP_200_OK_HEADER =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEEPALIVE_CRLF =
            "\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLOSE_CRLF =
            "\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    // Reusable per-instance parse buffer for the frame output
    private final ByteBuffer frameBuf = ByteBuffer.allocate(65536);

    // State carried from decode → encode for the current request/response pair.
    private boolean lastKeepAlive = true;
    private boolean lastWasHead = false;

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        int start = wire.position();
        int limit = wire.limit();

        // RFC 7230 §3.5: servers SHOULD tolerate at least one CRLF before the
        // request line (some clients emit stray CRLFs between pipelined requests).
        while (start < limit - 1 && wire.get(start) == '\r' && wire.get(start + 1) == '\n') {
            start += 2;
        }
        if (start >= limit) { wire.position(start); return null; }
        wire.position(start);

        int headerEnd = indexOf(wire, CRLF_CRLF);
        if (headerEnd < 0) {
            if (limit - start > MAX_HEADER_SIZE) {
                throw fail(431, "Header section exceeds " + MAX_HEADER_SIZE + " bytes");
            }
            return null;
        }
        if (headerEnd - start > MAX_HEADER_SIZE) {
            throw fail(431, "Header section exceeds " + MAX_HEADER_SIZE + " bytes");
        }

        // Request line ends at the first CRLF (which equals headerEnd for a
        // header-less request).
        int requestLineEnd = indexOfCrlf(wire, start, headerEnd + 2);
        if (requestLineEnd < 0) throw fail(400, "Malformed request line");

        int methodEnd = indexOfByte(wire, start, requestLineEnd, (byte) ' ');
        if (methodEnd < 0 || methodEnd == start) throw fail(400, "Malformed request line: missing method");

        int pathEnd = indexOfByte(wire, methodEnd + 1, requestLineEnd, (byte) ' ');
        if (pathEnd < 0 || pathEnd == methodEnd + 1) {
            // Missing second space → HTTP/0.9 "GET /" style (no version) or junk.
            throw fail(400, "Malformed request line: missing HTTP version");
        }

        int pathLen = pathEnd - methodEnd - 1;
        if (pathLen > MAX_HEADER_SIZE) throw fail(414, "Request URI exceeds " + MAX_HEADER_SIZE + " bytes");

        // HTTP-version token must be exactly "HTTP/1.0" or "HTTP/1.1".
        int versionStart = pathEnd + 1;
        int versionLen = requestLineEnd - versionStart;
        if (versionLen != 8
                || wire.get(versionStart)     != 'H' || wire.get(versionStart + 1) != 'T'
                || wire.get(versionStart + 2) != 'T' || wire.get(versionStart + 3) != 'P'
                || wire.get(versionStart + 4) != '/' || wire.get(versionStart + 5) != '1'
                || wire.get(versionStart + 6) != '.'
                || (wire.get(versionStart + 7) != '0' && wire.get(versionStart + 7) != '1')) {
            throw fail(505, "Unsupported HTTP version");
        }
        boolean http10 = wire.get(versionStart + 7) == '0';

        int headersStart = requestLineEnd + 2;

        // Reject Transfer-Encoding — we do not implement dechunking.
        if (hasHeaderAtLineStart(wire, headersStart, headerEnd, TRANSFER_ENCODING)) {
            throw fail(501, "Transfer-Encoding not supported");
        }

        // HTTP/1.1 requires a Host header (RFC 7230 §5.4).
        if (!http10 && !hasHeaderAtLineStart(wire, headersStart, headerEnd, HOST_HEADER)) {
            throw fail(400, "HTTP/1.1 request missing Host header");
        }

        boolean bodyMethod = isBodyMethod(wire, start, methodEnd);

        int contentLength = findContentLength(wire, headersStart, headerEnd);
        if (contentLength == -2) throw fail(400, "Duplicate Content-Length header");
        if (contentLength == -3) throw fail(400, "Malformed Content-Length header");
        if (contentLength == -1) {
            if (bodyMethod) throw fail(411, "Missing Content-Length on body-bearing request");
            contentLength = 0;
        }
        if (contentLength > MAX_BODY_SIZE) throw fail(413, "Content-Length exceeds " + MAX_BODY_SIZE);

        int bodyStart = headerEnd + 4;
        if (wire.limit() - bodyStart < contentLength) return null;

        boolean keepAlive = resolveKeepAlive(wire, headersStart, headerEnd, http10);
        int methodLen = methodEnd - start;
        boolean isHead = methodLen == 4
                && wire.get(start)     == 'H' && wire.get(start + 1) == 'E'
                && wire.get(start + 2) == 'A' && wire.get(start + 3) == 'D';

        // Build frame into pre-allocated buffer (zero allocation)
        frameBuf.clear();
        frameBuf.putShort((short) methodLen);
        for (int i = 0; i < methodLen; i++) frameBuf.put(wire.get(start + i));
        frameBuf.putShort((short) pathLen);
        for (int i = 0; i < pathLen; i++) frameBuf.put(wire.get(methodEnd + 1 + i));
        frameBuf.putInt(contentLength);
        for (int i = 0; i < contentLength; i++) frameBuf.put(wire.get(bodyStart + i));

        wire.position(bodyStart + contentLength);
        frameBuf.flip();
        this.lastKeepAlive = keepAlive;
        this.lastWasHead = isHead;
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

        boolean keepAlive = this.lastKeepAlive;
        boolean suppressBody = this.lastWasHead;
        byte[] connectionLine = keepAlive ? KEEPALIVE_CRLF : CLOSE_CRLF;
        int writtenBodyLen = suppressBody ? 0 : bodyLen;

        // Fast path: 200 OK text/plain with keep-alive and a body — benchmark case.
        if (keepAlive && !suppressBody
                && status == 200 && reasonLen == 2
                && frame.get(reasonStart) == 'O' && frame.get(reasonStart + 1) == 'K'
                && isTextPlain(frame, ctypeStart, ctypeLen)) {
            out.put(HTTP_200_OK_HEADER);
            writeIntAsDecimal(out, bodyLen);
            out.put(KEEPALIVE_CRLF);
            for (int i = 0; i < bodyLen; i++) out.put(frame.get(bodyStart + i));
            return;
        }

        writeBytes(out, "HTTP/1.1 ");
        writeIntAsDecimal(out, status);
        out.put((byte) ' ');
        for (int i = 0; i < reasonLen; i++) out.put(frame.get(reasonStart + i));
        writeBytes(out, "\r\nContent-Type: ");
        for (int i = 0; i < ctypeLen; i++) out.put(frame.get(ctypeStart + i));
        writeBytes(out, "\r\nContent-Length: ");
        // HEAD: advertise the would-be body length, then suppress the body itself.
        writeIntAsDecimal(out, bodyLen);
        out.put(connectionLine);
        for (int i = 0; i < writtenBodyLen; i++) out.put(frame.get(bodyStart + i));
    }

    /** Whether the most recently decoded request asked for a persistent connection. */
    public boolean lastKeepAlive() { return lastKeepAlive; }

    /**
     * Build a protocol exception and reset state so the caller's subsequent error
     * response is emitted with {@code Connection: close} (we can't safely reuse
     * a connection after a framing error).
     */
    private HttpProtocolException fail(int status, String msg) {
        this.lastKeepAlive = false;
        this.lastWasHead = false;
        return new HttpProtocolException(status, msg);
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
        int start = out.position();
        while (value > 0) { out.put((byte) ('0' + value % 10)); value /= 10; }
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

    /**
     * Scan headers [from,to) for the Content-Length header anchored at line starts.
     *   -1  absent
     *   -2  duplicated / conflicting
     *   -3  malformed (missing digits, non-numeric, overflow)
     *   n   the parsed value
     */
    private static int findContentLength(ByteBuffer wire, int from, int to) {
        int patLen = CONTENT_LENGTH.length;
        int found = -1;
        int lineStart = from;
        while (lineStart < to) {
            if (matchesCaseInsensitive(wire, lineStart, CONTENT_LENGTH)) {
                int numStart = lineStart + patLen;
                while (numStart < to && (wire.get(numStart) == ' ' || wire.get(numStart) == '\t')) numStart++;
                int digitStart = numStart;
                long value = 0;
                while (numStart < to) {
                    byte b = wire.get(numStart);
                    if (b < '0' || b > '9') break;
                    value = value * 10 + (b - '0');
                    if (value > Integer.MAX_VALUE) return -3;
                    numStart++;
                }
                if (numStart == digitStart) return -3;
                int parsed = (int) value;
                if (found >= 0 && found != parsed) return -2;
                found = parsed;
            }
            int next = indexOfCrlf(wire, lineStart, to);
            if (next < 0) break;
            lineStart = next + 2;
        }
        return found;
    }

    /** True if a header line in [{@code from},{@code to}) begins with {@code nameLower}. */
    private static boolean hasHeaderAtLineStart(ByteBuffer wire, int from, int to, byte[] nameLower) {
        int lineStart = from;
        while (lineStart < to) {
            if (matchesCaseInsensitive(wire, lineStart, nameLower)) return true;
            int next = indexOfCrlf(wire, lineStart, to);
            if (next < 0) return false;
            lineStart = next + 2;
        }
        return false;
    }

    /**
     * Resolve connection persistence from HTTP version + Connection header.
     *   HTTP/1.1 default: keep-alive unless {@code Connection: close}.
     *   HTTP/1.0 default: close     unless {@code Connection: keep-alive}.
     */
    private static boolean resolveKeepAlive(ByteBuffer wire, int from, int to, boolean http10) {
        int patLen = CONNECTION_HEADER.length;
        int lineStart = from;
        while (lineStart < to) {
            if (matchesCaseInsensitive(wire, lineStart, CONNECTION_HEADER)) {
                int v = lineStart + patLen;
                while (v < to && (wire.get(v) == ' ' || wire.get(v) == '\t')) v++;
                if (startsWithCaseInsensitive(wire, v, to, "close")) return false;
                if (startsWithCaseInsensitive(wire, v, to, "keep-alive")) return true;
            }
            int next = indexOfCrlf(wire, lineStart, to);
            if (next < 0) break;
            lineStart = next + 2;
        }
        return !http10;
    }

    private static boolean startsWithCaseInsensitive(ByteBuffer wire, int pos, int limit, String needle) {
        if (pos + needle.length() > limit) return false;
        for (int i = 0; i < needle.length(); i++) {
            byte b = wire.get(pos + i);
            byte n = (byte) needle.charAt(i);
            byte bl = isAsciiLetter(b) ? (byte) (b | 0x20) : b;
            byte nl = isAsciiLetter(n) ? (byte) (n | 0x20) : n;
            if (bl != nl) return false;
        }
        return true;
    }

    /**
     * Compares {@code wire[pos..pos+nameLower.length)} case-insensitively against
     * {@code nameLower} (which must be lowercase ASCII). Only letters are folded,
     * so non-letter bytes (':', '-', digits) compare exactly.
     */
    private static boolean matchesCaseInsensitive(ByteBuffer wire, int pos, byte[] nameLower) {
        if (pos + nameLower.length > wire.limit()) return false;
        for (int j = 0; j < nameLower.length; j++) {
            byte b = wire.get(pos + j);
            byte fold = isAsciiLetter(b) ? (byte) (b | 0x20) : b;
            if (fold != nameLower[j]) return false;
        }
        return true;
    }

    private static boolean isAsciiLetter(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z');
    }

    /**
     * True if this request's method carries a body that requires Content-Length
     * framing (POST, PUT, PATCH). Others (GET, HEAD, DELETE, OPTIONS) may omit
     * Content-Length.
     */
    private static boolean isBodyMethod(ByteBuffer wire, int start, int methodEnd) {
        int len = methodEnd - start;
        return matchesAscii(wire, start, len, "POST")
                || matchesAscii(wire, start, len, "PUT")
                || matchesAscii(wire, start, len, "PATCH");
    }

    private static boolean matchesAscii(ByteBuffer wire, int start, int len, String expected) {
        if (len != expected.length()) return false;
        for (int i = 0; i < len; i++) {
            if (wire.get(start + i) != (byte) expected.charAt(i)) return false;
        }
        return true;
    }

    private static int indexOfCrlf(ByteBuffer buf, int from, int to) {
        for (int i = from; i < to - 1; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n') return i;
        }
        return -1;
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

    /**
     * Build a minimal error response frame for the given status code. Used by
     * servers to respond to malformed input before closing the connection.
     */
    public static ByteBuffer buildErrorResponseFrame(int status) {
        String reason = switch (status) {
            case 400 -> "Bad Request";
            case 411 -> "Length Required";
            case 413 -> "Payload Too Large";
            case 414 -> "URI Too Long";
            case 431 -> "Request Header Fields Too Large";
            case 501 -> "Not Implemented";
            case 505 -> "HTTP Version Not Supported";
            default  -> "Error";
        };
        return buildResponseFrame(status, reason, "text/plain", new byte[0]);
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
