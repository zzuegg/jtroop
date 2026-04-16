package jtroop.pipeline;

import jtroop.pipeline.layers.HttpLayer;
import jtroop.pipeline.layers.HttpLayer.HttpProtocolException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpLayerTest {

    private static ByteBuffer wire(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Baseline behaviour — happy paths.
    // ------------------------------------------------------------------

    @Test
    void decodeInbound_parsesSimpleGet() {
        var layer = new HttpLayer();
        var frame = layer.decodeInbound(wire("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertNotNull(frame);
        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("GET", parsed.method());
        assertEquals("/hello", parsed.path());
        assertEquals(0, parsed.body().length);
    }

    @Test
    void decodeInbound_parsesPostWithBody() {
        var layer = new HttpLayer();
        var body = "{\"name\":\"alice\"}";
        var req = "POST /users HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                + body.length() + "\r\n\r\n" + body;

        var frame = layer.decodeInbound(wire(req));
        assertNotNull(frame);
        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("POST", parsed.method());
        assertEquals("/users", parsed.path());
        assertEquals(body, new String(parsed.body(), StandardCharsets.UTF_8));
    }

    @Test
    void decodeInbound_returnsNullOnIncompleteRequest() {
        var layer = new HttpLayer();
        assertNull(layer.decodeInbound(wire("GET /hel")));
    }

    @Test
    void decodeInbound_returnsNullOnIncompleteBody() {
        var layer = new HttpLayer();
        var partial = "POST /x HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\nhi";
        assertNull(layer.decodeInbound(wire(partial)));
    }

    @Test
    void encodeOutbound_emitsValidHttpResponse() {
        var layer = new HttpLayer();
        // Seed state as if a successful GET had just been decoded.
        layer.decodeInbound(wire("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"));

        var respFrame = HttpLayer.buildResponseFrame(200, "OK", "application/json",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        var out = ByteBuffer.allocate(1024);
        layer.encodeOutbound(respFrame, out);
        out.flip();

        var response = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(response.contains("Content-Type: application/json"));
        assertTrue(response.contains("Content-Length: 11"));
        assertTrue(response.endsWith("{\"ok\":true}"));
    }

    @Test
    void parseFrame_buildFrame_roundtrip() {
        var frame = HttpLayer.buildRequestFrame("POST", "/api", "hello".getBytes(StandardCharsets.UTF_8));
        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("POST", parsed.method());
        assertEquals("/api", parsed.path());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), parsed.body());
    }

    @Test
    void roundtrip_request_response() {
        var server = new HttpLayer();
        var reqFrame = server.decodeInbound(wire("GET /test HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertNotNull(reqFrame);
        var req = HttpLayer.parseFrame(reqFrame);
        assertEquals("GET", req.method());
        assertEquals("/test", req.path());

        var respFrame = HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                "pong".getBytes(StandardCharsets.UTF_8));
        var respWire = ByteBuffer.allocate(256);
        server.encodeOutbound(respFrame, respWire);
        respWire.flip();

        var respStr = StandardCharsets.UTF_8.decode(respWire).toString();
        assertTrue(respStr.contains("200 OK"));
        assertTrue(respStr.endsWith("pong"));
    }

    // ------------------------------------------------------------------
    // HTTP/1.0 — no Host required, default connection: close.
    // ------------------------------------------------------------------

    @Test
    void http10_noHost_isAccepted() {
        var layer = new HttpLayer();
        var frame = layer.decodeInbound(wire("GET /x HTTP/1.0\r\n\r\n"));
        assertNotNull(frame);
        assertFalse(layer.lastKeepAlive(), "HTTP/1.0 default is close");
    }

    @Test
    void http10_keepAliveExplicit_persistsConnection() {
        var layer = new HttpLayer();
        assertNotNull(layer.decodeInbound(wire("GET /x HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")));
        assertTrue(layer.lastKeepAlive());
    }

    @Test
    void http10_response_usesConnectionClose() {
        var layer = new HttpLayer();
        layer.decodeInbound(wire("GET /x HTTP/1.0\r\n\r\n"));
        var respFrame = HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                "hi".getBytes(StandardCharsets.UTF_8));
        var out = ByteBuffer.allocate(256);
        layer.encodeOutbound(respFrame, out);
        out.flip();
        var s = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(s.contains("Connection: close"), s);
        assertFalse(s.contains("Connection: keep-alive"));
    }

    @Test
    void http11_connectionClose_respectedOnResponse() {
        var layer = new HttpLayer();
        layer.decodeInbound(wire("GET /x HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"));
        assertFalse(layer.lastKeepAlive());
    }

    // ------------------------------------------------------------------
    // Transfer-Encoding: we do not dechunk — 501.
    // ------------------------------------------------------------------

    @Test
    void transferEncodingChunked_rejected501() {
        var layer = new HttpLayer();
        var req = "POST /x HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n";
        var e = assertThrows(HttpProtocolException.class, () -> layer.decodeInbound(wire(req)));
        assertEquals(501, e.status());
    }

    @Test
    void transferEncodingChunked_caseInsensitive() {
        var layer = new HttpLayer();
        var req = "POST /x HTTP/1.1\r\nHost: h\r\nTRANSFER-ENCODING: Chunked\r\n\r\n";
        var e = assertThrows(HttpProtocolException.class, () -> layer.decodeInbound(wire(req)));
        assertEquals(501, e.status());
    }

    // ------------------------------------------------------------------
    // HEAD: method parsed, response body suppressed but Content-Length advertised.
    // ------------------------------------------------------------------

    @Test
    void head_parsed_andBodySuppressedOnResponse() {
        var layer = new HttpLayer();
        var frame = layer.decodeInbound(wire("HEAD /page HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertNotNull(frame);
        assertEquals("HEAD", HttpLayer.parseFrame(frame).method());

        var respFrame = HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                "bodybytes".getBytes(StandardCharsets.UTF_8));
        var out = ByteBuffer.allocate(512);
        layer.encodeOutbound(respFrame, out);
        out.flip();
        var s = StandardCharsets.UTF_8.decode(out).toString();

        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(s.contains("Content-Length: 9"));
        assertFalse(s.contains("bodybytes"), "HEAD response must not include body: " + s);
    }

    // ------------------------------------------------------------------
    // Long URI — rejected.
    // ------------------------------------------------------------------

    @Test
    void longUri_rejected() {
        var layer = new HttpLayer();
        var path = "/" + "a".repeat(HttpLayer.MAX_HEADER_SIZE + 10);
        var req = "GET " + path + " HTTP/1.1\r\nHost: h\r\n\r\n";
        var e = assertThrows(HttpProtocolException.class, () -> layer.decodeInbound(wire(req)));
        // Either 414 (URI Too Long) or 431 (Header Fields Too Large) is acceptable;
        // whole-header size exceeds the limit before we even get to the path check.
        assertTrue(e.status() == 414 || e.status() == 431, "status was " + e.status());
    }

    // ------------------------------------------------------------------
    // HTTP/1.1 requires Host header.
    // ------------------------------------------------------------------

    @Test
    void http11_missingHost_rejected400() {
        var layer = new HttpLayer();
        var e = assertThrows(HttpProtocolException.class,
                () -> layer.decodeInbound(wire("GET /x HTTP/1.1\r\n\r\n")));
        assertEquals(400, e.status());
    }

    // ------------------------------------------------------------------
    // Leading CRLF before the request line is tolerated (RFC 7230 §3.5).
    // ------------------------------------------------------------------

    @Test
    void leadingCrlf_tolerated() {
        var layer = new HttpLayer();
        var frame = layer.decodeInbound(wire("\r\n\r\nGET /x HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertNotNull(frame);
        assertEquals("GET", HttpLayer.parseFrame(frame).method());
    }

    // ------------------------------------------------------------------
    // Pipelined requests — two in one buffer.
    // ------------------------------------------------------------------

    @Test
    void pipelinedRequests_decodedSequentially() {
        var layer = new HttpLayer();
        var combined = "GET /a HTTP/1.1\r\nHost: h\r\n\r\n"
                     + "GET /b HTTP/1.1\r\nHost: h\r\n\r\n";
        var buf = wire(combined);

        var f1 = layer.decodeInbound(buf);
        assertNotNull(f1);
        assertEquals("/a", HttpLayer.parseFrame(f1).path());

        var f2 = layer.decodeInbound(buf);
        assertNotNull(f2);
        assertEquals("/b", HttpLayer.parseFrame(f2).path());

        assertNull(layer.decodeInbound(buf));
    }

    // ------------------------------------------------------------------
    // HTTP/0.9 style ("GET /\r\n") — no version token, rejected 400.
    // ------------------------------------------------------------------

    @Test
    void http09_rejected400() {
        var layer = new HttpLayer();
        var e = assertThrows(HttpProtocolException.class,
                () -> layer.decodeInbound(wire("GET /\r\n\r\n")));
        assertEquals(400, e.status());
    }

    @Test
    void unsupportedHttpVersion_rejected505() {
        var layer = new HttpLayer();
        var e = assertThrows(HttpProtocolException.class,
                () -> layer.decodeInbound(wire("GET / HTTP/2.0\r\nHost: h\r\n\r\n")));
        assertEquals(505, e.status());
    }

    // ------------------------------------------------------------------
    // Case-insensitive Content-Length scanning.
    // ------------------------------------------------------------------

    @Test
    void contentLength_caseInsensitive_lowercase() {
        var layer = new HttpLayer();
        var body = "abc";
        var req = "POST /x HTTP/1.1\r\nHost: h\r\ncontent-length: 3\r\n\r\n" + body;
        var frame = layer.decodeInbound(wire(req));
        assertNotNull(frame);
        assertEquals("abc", new String(HttpLayer.parseFrame(frame).body(), StandardCharsets.UTF_8));
    }

    @Test
    void contentLength_caseInsensitive_mixedCase() {
        var layer = new HttpLayer();
        var body = "abc";
        var req = "POST /x HTTP/1.1\r\nHost: h\r\nContent-length: 3\r\n\r\n" + body;
        var frame = layer.decodeInbound(wire(req));
        assertNotNull(frame);
        assertEquals("abc", new String(HttpLayer.parseFrame(frame).body(), StandardCharsets.UTF_8));
    }

    @Test
    void contentLength_caseInsensitive_uppercase() {
        var layer = new HttpLayer();
        var body = "abc";
        var req = "POST /x HTTP/1.1\r\nHost: h\r\nCONTENT-LENGTH: 3\r\n\r\n" + body;
        var frame = layer.decodeInbound(wire(req));
        assertNotNull(frame);
        assertEquals("abc", new String(HttpLayer.parseFrame(frame).body(), StandardCharsets.UTF_8));
    }

    @Test
    void contentLength_inHeaderValue_notMatched() {
        // A header VALUE containing "content-length:" text must not be matched;
        // the scan is anchored at line starts.
        var layer = new HttpLayer();
        var body = "hello";
        var req = "POST /x HTTP/1.1\r\nHost: h\r\n"
                + "X-Note: content-length: 9999\r\n"
                + "Content-Length: 5\r\n\r\n" + body;
        var frame = layer.decodeInbound(wire(req));
        assertNotNull(frame);
        assertEquals("hello", new String(HttpLayer.parseFrame(frame).body(), StandardCharsets.UTF_8));
    }

    @Test
    void contentLength_duplicateConflicting_rejected() {
        var layer = new HttpLayer();
        var req = "POST /x HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabcd";
        var e = assertThrows(HttpProtocolException.class, () -> layer.decodeInbound(wire(req)));
        assertEquals(400, e.status());
    }

    @Test
    void contentLength_missingOnPost_rejected411() {
        var layer = new HttpLayer();
        var e = assertThrows(HttpProtocolException.class,
                () -> layer.decodeInbound(wire("POST /x HTTP/1.1\r\nHost: h\r\n\r\n")));
        assertEquals(411, e.status());
    }

    @Test
    void errorFrame_forcesConnectionClose() {
        var layer = new HttpLayer();
        assertThrows(HttpProtocolException.class,
                () -> layer.decodeInbound(wire("POST /x HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n")));
        // After a rejected request the layer must encode subsequent responses
        // with Connection: close so the connection is shut down cleanly.
        var out = ByteBuffer.allocate(256);
        layer.encodeOutbound(HttpLayer.buildErrorResponseFrame(501), out);
        out.flip();
        var s = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(s.contains("Connection: close"), s);
    }
}
