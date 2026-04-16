package bench;

import jtroop.core.EventLoop;
import jtroop.core.EventLoopGroup;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.HttpLayer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;

/**
 * Standalone multi-threaded HTTP server for external wrk/ab benchmarking.
 * Uses EventLoopGroup to distribute connections across worker threads.
 *
 * Run:  gradle :benchmark:common:runHttp
 * Test: wrk -t4 -c100 -d10s http://localhost:8080/
 */
public class HttpServerMain {

    private static final byte[] HELLO_BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int workerCount = args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();

        var acceptLoop = new EventLoop("http-accept");
        var workerGroup = new EventLoopGroup(workerCount);

        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));

        acceptLoop.start();
        workerGroup.start();

        acceptLoop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(acceptLoop.selector(), SelectionKey.OP_ACCEPT,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isAcceptable()) {
                                acceptClient(serverChannel, workerGroup);
                            }
                        });
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        System.out.println("HTTP server listening on port " + port
                + " with " + workerCount + " worker threads");
        System.out.println("Test with: wrk -t4 -c100 -d10s http://localhost:" + port + "/");

        Thread.currentThread().join();
    }

    private static void acceptClient(ServerSocketChannel serverChannel,
                                      EventLoopGroup workerGroup) throws IOException {
        var client = serverChannel.accept();
        if (client == null) return;
        client.configureBlocking(false);

        // Per-connection state — each worker has its own pipeline instance
        var readBuf = ByteBuffer.allocate(65536);
        var pipeline = new Pipeline(new HttpLayer());

        var worker = workerGroup.next();
        worker.submit(() -> {
            try {
                client.register(worker.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) rk -> handleRead(rk, readBuf, pipeline));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
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
