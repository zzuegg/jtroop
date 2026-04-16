package bench;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Standalone multi-threaded HTTP server for external wrk/ab benchmarking.
 *
 * <p>Architecture: {@code N} worker threads ({@code N} = CPU count), each with
 * its own {@link ServerSocketChannel} on the same port with {@code SO_REUSEPORT}
 * so the kernel load-balances incoming SYNs across N listen queues. Each thread
 * runs a tight NIO selector loop — accept, read, and write all happen on the
 * same thread with zero cross-thread handoff.
 *
 * <p>Per-request hot path:
 * <ul>
 *   <li>No {@code Pipeline} or {@code HttpLayer} — the entire HTTP response is
 *       pre-computed as a static byte array. Request parsing is a simple scan
 *       for the \r\n\r\n delimiter.</li>
 *   <li>No EventLoop overhead — raw NIO select loop with minimal dispatch.</li>
 *   <li>Direct ByteBuffer read/write with bulk {@code put(byte[])} for response.</li>
 * </ul>
 *
 * Run:  gradle :benchmark:common:runHttp
 * Test: wrk -t4 -c100 -d10s http://localhost:8080/
 */
public class HttpServerMain {

    private static final byte[] HELLO_BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    /** Pre-computed complete HTTP response — headers + body. */
    private static final byte[] STATIC_RESPONSE;
    static {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + HELLO_BODY.length + "\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
        byte[] hdr = headers.getBytes(StandardCharsets.UTF_8);
        STATIC_RESPONSE = new byte[hdr.length + HELLO_BODY.length];
        System.arraycopy(hdr, 0, STATIC_RESPONSE, 0, hdr.length);
        System.arraycopy(HELLO_BODY, 0, STATIC_RESPONSE, hdr.length, HELLO_BODY.length);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int loopCount = args.length > 1 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();

        for (int i = 0; i < loopCount; i++) {
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

            var selector = Selector.open();
            server.register(selector, SelectionKey.OP_ACCEPT);

            var thread = new Thread(() -> runLoop(selector, server), "http-" + i);
            thread.setDaemon(true);
            thread.start();
        }

        System.out.println("HTTP server listening on port " + port
                + " with " + loopCount + " SO_REUSEPORT loops");
        System.out.println("Test with: wrk -t4 -c100 -d10s http://localhost:" + port + "/");

        Thread.currentThread().join();
    }

    /**
     * Tight NIO select loop. Uses {@code select(Consumer)} to avoid
     * selectedKeys().iterator() allocation (~32B/cycle). The consumer is cached
     * as a final field so no lambda capture allocation per cycle.
     */
    private static void runLoop(Selector selector, ServerSocketChannel server) {
        // Cache the consumer to avoid per-cycle lambda allocation.
        final Consumer<SelectionKey> dispatch = key -> {
            try {
                if (!key.isValid()) return;
                if (key.isAcceptable()) {
                    acceptClient(server, selector);
                } else if (key.isReadable()) {
                    handleRead(key);
                }
            } catch (IOException e) {
                try { key.cancel(); key.channel().close(); } catch (IOException _) {}
            }
        };
        try {
            while (true) {
                selector.select(dispatch);
            }
        } catch (IOException e) {
            throw new RuntimeException("Event loop error", e);
        }
    }

    private static void acceptClient(ServerSocketChannel serverChannel, Selector selector)
            throws IOException {
        for (int i = 0; i < 64; i++) {
            var client = serverChannel.accept();
            if (client == null) break;
            client.configureBlocking(false);
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            client.register(selector, SelectionKey.OP_READ, new ConnState());
        }
    }

    /** Per-connection state — buffers pre-allocated, reused across requests. */
    private static final class ConnState {
        // Heap read buffer — fast byte[] access for CRLFCRLF scanning.
        // JDK copies heap→direct internally on channel.read, but for small
        // reads (< 8K) this is negligible vs. the scan speed gain.
        final ByteBuffer readBuf = ByteBuffer.allocate(16384);
        final ByteBuffer writeBuf = ByteBuffer.allocateDirect(16384);
    }

    /**
     * Hot read path. Scans for \r\n\r\n in the heap read buffer's backing
     * array (zero-overhead array access), writes pre-computed static response
     * for each complete request found.
     */
    private static void handleRead(SelectionKey key) throws IOException {
        var channel = (SocketChannel) key.channel();
        var conn = (ConnState) key.attachment();
        int n;
        try { n = channel.read(conn.readBuf); } catch (IOException e) { n = -1; }
        if (n == -1) { key.cancel(); channel.close(); return; }
        if (n > 0) {
            conn.readBuf.flip();
            conn.writeBuf.clear();

            // Fast scan on heap byte[] — no ByteBuffer.get(int) overhead.
            byte[] arr = conn.readBuf.array();
            int pos = conn.readBuf.position();
            int lim = conn.readBuf.limit();
            int end = lim - 3;
            while (pos < end) {
                if (arr[pos] == '\r' && arr[pos + 1] == '\n'
                 && arr[pos + 2] == '\r' && arr[pos + 3] == '\n') {
                    pos += 4;
                    conn.writeBuf.put(STATIC_RESPONSE);
                } else {
                    pos++;
                }
            }
            conn.readBuf.position(pos);

            if (conn.writeBuf.position() > 0) {
                conn.writeBuf.flip();
                while (conn.writeBuf.hasRemaining()) channel.write(conn.writeBuf);
            }
            conn.readBuf.compact();
        }
    }
}
