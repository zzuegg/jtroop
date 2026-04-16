package bench;

import jtroop.core.EventLoop;
import jtroop.pipeline.layers.HttpLayer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;

/**
 * Standalone multi-threaded HTTP server for external wrk/ab benchmarking.
 *
 * <p>Architecture: {@code N} event loops ({@code N} = CPU count). Each loop
 * opens its own {@link ServerSocketChannel} on the same port with
 * {@code SO_REUSEPORT} enabled, so the kernel load-balances incoming SYNs
 * across the N listen queues. Each loop accepts its connections <em>and</em>
 * runs read/write for them — no cross-thread handoff on accept, no lock
 * contention on a shared accept queue, and the worker that read the SYN is
 * the one that services the conversation.
 *
 * <p>Per-request hot path:
 * <ul>
 *   <li>No {@code Pipeline} indirection — we talk to the one {@link HttpLayer}
 *       directly, saving one heap-to-direct memcpy per response.</li>
 *   <li>No {@code HttpLayer.buildResponseFrame} — the response frame is
 *       bit-identical across requests, so we build it exactly once per
 *       connection and rewind the cursor before each encode. Eliminates the
 *       per-request ByteBuffer allocation that was on the hot loop body.</li>
 * </ul>
 *
 * <p>The response is still generated per request through
 * {@link HttpLayer#encodeOutbound} — header line, Content-Length digits, and
 * connection token are all re-emitted. Only the source frame is reused.
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
        int loopCount = args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();

        // N accept+worker loops, each with its own SO_REUSEPORT listen socket.
        for (int i = 0; i < loopCount; i++) {
            var loop = new EventLoop("http-" + i);
            var server = ServerSocketChannel.open();
            server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            try {
                server.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            } catch (UnsupportedOperationException e) {
                System.err.println("SO_REUSEPORT not supported on this platform; "
                        + "only the first listener will receive connections");
            }
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port), 1024);

            loop.start();
            loop.submit(() -> {
                try {
                    server.register(loop.selector(), SelectionKey.OP_ACCEPT,
                            (EventLoop.KeyHandler) key -> {
                                if (key.isAcceptable()) acceptClient(server, loop);
                            });
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        System.out.println("HTTP server listening on port " + port
                + " with " + loopCount + " SO_REUSEPORT loops");
        System.out.println("Test with: wrk -t4 -c100 -d10s http://localhost:" + port + "/");

        Thread.currentThread().join();
    }

    private static void acceptClient(ServerSocketChannel serverChannel, EventLoop worker)
            throws IOException {
        // Drain the accept queue in one go — with SO_REUSEPORT we each get our
        // own queue but we still want to pick up bursts without a round-trip
        // through select().
        for (int i = 0; i < 64; i++) {
            var client = serverChannel.accept();
            if (client == null) break;
            client.configureBlocking(false);
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            var conn = new ConnState();
            client.register(worker.selector(), SelectionKey.OP_READ,
                    (EventLoop.KeyHandler) rk -> handleRead(rk, conn));
        }
    }

    /**
     * Per-connection state — all buffers pre-allocated, reused across requests.
     *
     * <p>{@code respFrame} is the intermediate binary frame
     * ({@code HttpLayer.buildResponseFrame} output). Bit-identical across the
     * benchmark's requests, so we construct it once and {@code position(0)}
     * before each {@link HttpLayer#encodeOutbound} call. This removes the only
     * per-request allocation that was on the hot path.
     */
    private static final class ConnState {
        final ByteBuffer readBuf = ByteBuffer.allocateDirect(8192);
        final ByteBuffer writeBuf = ByteBuffer.allocateDirect(8192);
        final HttpLayer http = new HttpLayer();
        final ByteBuffer respFrame = HttpLayer.buildResponseFrame(
                200, OK_REASON, TEXT_PLAIN, HELLO_BODY);
    }

    private static void handleRead(SelectionKey key, ConnState conn) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n;
        try { n = channel.read(conn.readBuf); } catch (IOException e) { n = -1; }
        if (n == -1) { key.cancel(); channel.close(); return; }
        if (n > 0) {
            conn.readBuf.flip();
            conn.writeBuf.clear();
            var http = conn.http;
            var respFrame = conn.respFrame;
            var frame = http.decodeInbound(conn.readBuf);
            while (frame != null) {
                respFrame.position(0);
                http.encodeOutbound(respFrame, conn.writeBuf);
                frame = http.decodeInbound(conn.readBuf);
            }
            if (conn.writeBuf.position() > 0) {
                conn.writeBuf.flip();
                while (conn.writeBuf.hasRemaining()) channel.write(conn.writeBuf);
            }
            conn.readBuf.compact();
        }
    }
}
