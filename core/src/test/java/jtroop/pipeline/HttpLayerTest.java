package jtroop.pipeline;

import jtroop.pipeline.layers.HttpLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpLayerTest {

    @Test
    void decodeInbound_parsesSimpleGet() {
        var layer = new HttpLayer();
        var request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
        var wire = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        var frame = layer.decodeInbound(wire);
        assertNotNull(frame);

        // Frame contains: method + path + body as length-prefixed strings
        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("GET", parsed.method());
        assertEquals("/hello", parsed.path());
        assertEquals(0, parsed.body().length);
    }

    @Test
    void decodeInbound_parsesPostWithBody() {
        var layer = new HttpLayer();
        var body = "{\"name\":\"alice\"}";
        var request = "POST /users HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        var wire = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));

        var frame = layer.decodeInbound(wire);
        assertNotNull(frame);

        var parsed = HttpLayer.parseFrame(frame);
        assertEquals("POST", parsed.method());
        assertEquals("/users", parsed.path());
        assertEquals(body, new String(parsed.body(), StandardCharsets.UTF_8));
    }

    @Test
    void decodeInbound_returnsNullOnIncompleteRequest() {
        var layer = new HttpLayer();
        var wire = ByteBuffer.wrap("GET /hel".getBytes(StandardCharsets.UTF_8));
        assertNull(layer.decodeInbound(wire));
    }

    @Test
    void decodeInbound_returnsNullOnIncompleteBody() {
        var layer = new HttpLayer();
        var partial = "POST /x HTTP/1.1\r\nContent-Length: 100\r\n\r\nhi";
        var wire = ByteBuffer.wrap(partial.getBytes(StandardCharsets.UTF_8));
        assertNull(layer.decodeInbound(wire));
    }

    @Test
    void encodeOutbound_emitsValidHttpResponse() {
        var layer = new HttpLayer();
        var responseFrame = HttpLayer.buildResponseFrame(200, "OK", "application/json",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        var out = ByteBuffer.allocate(1024);
        layer.encodeOutbound(responseFrame, out);
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
        var serverLayer = new HttpLayer();
        var clientLayer = new HttpLayer();

        // Client sends raw HTTP bytes
        var requestBytes = "GET /test HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        var wire = ByteBuffer.wrap(requestBytes);

        // Server decodes to frame
        var reqFrame = serverLayer.decodeInbound(wire);
        var req = HttpLayer.parseFrame(reqFrame);
        assertEquals("GET", req.method());
        assertEquals("/test", req.path());

        // Server builds response frame and encodes to wire
        var respFrame = HttpLayer.buildResponseFrame(200, "OK", "text/plain",
                "pong".getBytes(StandardCharsets.UTF_8));
        var respWire = ByteBuffer.allocate(256);
        serverLayer.encodeOutbound(respFrame, respWire);
        respWire.flip();

        var respStr = StandardCharsets.UTF_8.decode(respWire).toString();
        assertTrue(respStr.contains("200 OK"));
        assertTrue(respStr.endsWith("pong"));
    }
}
