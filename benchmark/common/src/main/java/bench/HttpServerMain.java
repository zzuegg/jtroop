package bench;

import jtroop.core.EventLoop;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.HttpLayer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;

/**
 * Standalone HTTP server for external wrk/ab benchmarking.
 * Run: gradle :benchmark:common:runHttp
 * Test: wrk -t4 -c100 -d10s http://localhost:8080/
 */
public class HttpServerMain {

    private static final byte[] HELLO_BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        var eventLoop = new EventLoop("http-server");
        var pipeline = new Pipeline(new HttpLayer());

        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        eventLoop.start();

        eventLoop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(eventLoop.selector(), SelectionKey.OP_ACCEPT,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isAcceptable()) {
                                var client = serverChannel.accept();
                                if (client == null) return;
                                client.configureBlocking(false);
                                var readBuf = ByteBuffer.allocate(65536);
                                client.register(eventLoop.selector(), SelectionKey.OP_READ,
                                        (EventLoop.KeyHandler) rk -> handleRead(rk, readBuf, pipeline));
                            }
                        });
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        System.out.println("HTTP server listening on port " + port);
        System.out.println("Test with: wrk -t4 -c100 -d10s http://localhost:" + port + "/");

        // Keep running until interrupted
        Thread.currentThread().join();
    }

    private static void handleRead(SelectionKey key, ByteBuffer readBuf, Pipeline pipeline) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n;
        try { n = channel.read(readBuf); } catch (IOException e) { n = -1; }
        if (n == -1) { key.cancel(); channel.close(); return; }
        if (n > 0) {
            readBuf.flip();
            var frame = pipeline.decodeInbound(readBuf);
            while (frame != null) {
                // Just return "Hello, World!" for any request
                var respFrame = HttpLayer.buildResponseFrame(200, "OK", "text/plain", HELLO_BODY);
                var wire = ByteBuffer.allocate(8192);
                pipeline.encodeOutbound(respFrame, wire);
                wire.flip();
                while (wire.hasRemaining()) channel.write(wire);
                frame = pipeline.decodeInbound(readBuf);
            }
            readBuf.compact();
        }
    }
}
