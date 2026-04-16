package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 layer — parses incoming HTTP requests into intermediate binary frames
 * and emits outbound HTTP responses from response frames.
 *
 * The intermediate frame format is compatible with our codec/service dispatch:
 *   Request frame:  [method_len(u16)][method][path_len(u16)][path][body_len(u32)][body]
 *   Response frame: [status(u16)][reason_len(u16)][reason][ctype_len(u16)][ctype][body_len(u32)][body]
 *
 * Designed for wrk-compatible HTTP/1.1 benchmarking.
 * Supports keep-alive, Content-Length. No chunked encoding.
 */
public final class HttpLayer implements Layer {

    public record ParsedRequest(String method, String path, byte[] body) {}

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        // Find end of headers (CRLF CRLF)
        int headerEnd = indexOf(wire, CRLF_CRLF);
        if (headerEnd < 0) return null;

        int headerLen = headerEnd - wire.position();
        var headerBytes = new byte[headerLen];
        wire.mark();
        wire.get(headerBytes);
        wire.position(wire.position() + 4); // skip CRLF CRLF
        var headerStr = new String(headerBytes, StandardCharsets.UTF_8);

        // Parse request line: "METHOD /path HTTP/1.1"
        // If no \r found, the whole headerStr is the request line (no additional headers)
        int firstLineEnd = headerStr.indexOf('\r');
        var requestLine = (firstLineEnd < 0) ? headerStr : headerStr.substring(0, firstLineEnd);
        var parts = requestLine.split(" ", 3);
        if (parts.length < 3) { wire.reset(); return null; }
        var method = parts[0];
        var path = parts[1];

        // Parse Content-Length
        int contentLength = 0;
        var clIdx = headerStr.toLowerCase().indexOf("content-length:");
        if (clIdx >= 0) {
            int lineEnd = headerStr.indexOf('\r', clIdx);
            if (lineEnd < 0) lineEnd = headerStr.length();
            var clStr = headerStr.substring(clIdx + 15, lineEnd).trim();
            try { contentLength = Integer.parseInt(clStr); } catch (NumberFormatException _) {}
        }

        // Check if body is complete
        if (wire.remaining() < contentLength) {
            wire.reset();
            return null;
        }

        var body = new byte[contentLength];
        wire.get(body);

        return buildRequestFrame(method, path, body);
    }

    @Override
    public void encodeOutbound(ByteBuffer frame, ByteBuffer out) {
        // Frame format: [status(u16)][reason_len(u16)][reason][ctype_len(u16)][ctype][body_len(u32)][body]
        int status = frame.getShort() & 0xFFFF;
        int reasonLen = frame.getShort() & 0xFFFF;
        var reasonBytes = new byte[reasonLen];
        frame.get(reasonBytes);
        int ctypeLen = frame.getShort() & 0xFFFF;
        var ctypeBytes = new byte[ctypeLen];
        frame.get(ctypeBytes);
        int bodyLen = frame.getInt();
        var body = new byte[bodyLen];
        frame.get(body);

        // Emit: HTTP/1.1 {status} {reason}\r\nContent-Type: {ctype}\r\nContent-Length: {len}\r\nConnection: keep-alive\r\n\r\n{body}
        var sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ');
        sb.append(new String(reasonBytes, StandardCharsets.UTF_8)).append("\r\n");
        sb.append("Content-Type: ").append(new String(ctypeBytes, StandardCharsets.UTF_8)).append("\r\n");
        sb.append("Content-Length: ").append(bodyLen).append("\r\n");
        sb.append("Connection: keep-alive\r\n\r\n");
        out.put(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.put(body);
    }

    // --- Helpers for building/parsing frames ---

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
}
