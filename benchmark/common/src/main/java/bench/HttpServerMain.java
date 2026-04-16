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
 * Realistic: per-request response encoding through HttpLayer (no pre-built cheat).
 *
 * Run:  gradle :benchmark:common:runHttp
 * Test: wrk -t4 -c100 -d10s http://localhost:8080/
 */
public class HttpServerMain {

    private static final byte[] HELLO_BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String OK_REASON = "OK";
    private static final String TEXT_PLAIN = "text/plain";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int workerCount = args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();

        var acceptLoop = new EventLoop("http-accept");
        var workerGroup = new EventLoopGroup(workerCount);

        var serverChannel = ServerSocketChannel.open();
        serverChannel.socket().setReuseAddress(true);
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
        client.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);

        // Per-connection state — allocated ONCE, reused for all requests
        var conn = new ConnState();

        var worker = workerGroup.next();
        worker.submit(() -> {
            try {
                client.register(worker.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) rk -> handleRead(rk, conn));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Per-connection state — all buffers pre-allocated, reused across requests */
    private static final class ConnState {
        final ByteBuffer readBuf = ByteBuffer.allocate(8192);
        final ByteBuffer writeBuf = ByteBuffer.allocateDirect(8192);
        final Pipeline pipeline = new Pipeline(new HttpLayer());
    }

    private static void handleRead(SelectionKey key, ConnState conn) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n;
        try { n = channel.read(conn.readBuf); } catch (IOException e) { n = -1; }
        if (n == -1) { key.cancel(); channel.close(); return; }
        if (n > 0) {
            conn.readBuf.flip();
            var frame = conn.pipeline.decodeInbound(conn.readBuf);
            conn.writeBuf.clear();
            while (frame != null) {
                // Build response frame per-request via HttpLayer (realistic app workload)
                var respFrame = HttpLayer.buildResponseFrame(200, OK_REASON, TEXT_PLAIN, HELLO_BODY);
                conn.pipeline.encodeOutbound(respFrame, conn.writeBuf);
                frame = conn.pipeline.decodeInbound(conn.readBuf);
            }
            if (conn.writeBuf.position() > 0) {
                conn.writeBuf.flip();
                while (conn.writeBuf.hasRemaining()) channel.write(conn.writeBuf);
            }
            conn.readBuf.compact();
        }
    }
}
