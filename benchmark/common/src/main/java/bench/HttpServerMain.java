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
 * Zero-allocation hot path: per-connection buffers are reused, response is pre-built.
 *
 * Run:  gradle :benchmark:common:runHttp
 * Test: wrk -t4 -c100 -d10s http://localhost:8080/
 */
public class HttpServerMain {

    private static final byte[] HELLO_BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    // Pre-built response frame (reused across ALL connections — immutable after construction)
    private static final byte[] PREBUILT_RESPONSE = prebuildResponse();

    private static byte[] prebuildResponse() {
        var header = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + HELLO_BODY.length + "\r\n" +
                "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var result = new byte[header.length + HELLO_BODY.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(HELLO_BODY, 0, result, header.length, HELLO_BODY.length);
        return result;
    }

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

        // Per-connection state — allocated ONCE, reused for all requests on this connection
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
            // Batch all responses for this read into the write buffer
            conn.writeBuf.clear();
            int responses = 0;
            while (frame != null) {
                // Just put the pre-built response bytes directly (zero-alloc)
                if (conn.writeBuf.remaining() < PREBUILT_RESPONSE.length) {
                    // Flush what we have
                    conn.writeBuf.flip();
                    while (conn.writeBuf.hasRemaining()) channel.write(conn.writeBuf);
                    conn.writeBuf.clear();
                }
                conn.writeBuf.put(PREBUILT_RESPONSE);
                responses++;
                frame = conn.pipeline.decodeInbound(conn.readBuf);
            }
            if (responses > 0) {
                conn.writeBuf.flip();
                while (conn.writeBuf.hasRemaining()) channel.write(conn.writeBuf);
            }
            conn.readBuf.compact();
        }
    }
}
