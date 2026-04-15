package jtroop.transport;

import jtroop.core.EventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class TransportTest {

    @Test
    void eventLoop_startsAndStops() throws Exception {
        var loop = new EventLoop("test-loop");
        loop.start();
        assertTrue(loop.isRunning());
        loop.close();
        assertFalse(loop.isRunning());
    }

    @Test
    void eventLoop_executesSubmittedTask() throws Exception {
        var loop = new EventLoop("test-loop");
        loop.start();
        var latch = new CountDownLatch(1);
        loop.submit(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        loop.close();
    }

    @Test
    void tcp_serverAcceptsClientConnection() throws Exception {
        var loop = new EventLoop("test-loop");
        loop.start();

        var accepted = new CountDownLatch(1);

        // Open server socket
        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(0)); // random port
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        loop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(loop.selector(), SelectionKey.OP_ACCEPT,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isAcceptable()) {
                                var client = serverChannel.accept();
                                if (client != null) {
                                    client.close();
                                    accepted.countDown();
                                }
                            }
                        });
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        // Connect a client
        var clientChannel = SocketChannel.open();
        clientChannel.connect(new InetSocketAddress("localhost", port));

        assertTrue(accepted.await(2, TimeUnit.SECONDS));

        clientChannel.close();
        serverChannel.close();
        loop.close();
    }

    @Test
    void tcp_echoThroughEventLoop() throws Exception {
        var loop = new EventLoop("test-loop");
        loop.start();

        var received = new AtomicReference<Integer>();
        var done = new CountDownLatch(1);

        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(0));
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        loop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(loop.selector(), SelectionKey.OP_ACCEPT,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isAcceptable()) {
                                var client = serverChannel.accept();
                                if (client != null) {
                                    client.configureBlocking(false);
                                    client.register(loop.selector(), SelectionKey.OP_READ,
                                            (EventLoop.KeyHandler) readKey -> {
                                                if (readKey.isReadable()) {
                                                    var buf = ByteBuffer.allocate(64);
                                                    var ch = (SocketChannel) readKey.channel();
                                                    int n = ch.read(buf);
                                                    if (n > 0) {
                                                        buf.flip();
                                                        received.set(buf.getInt());
                                                        done.countDown();
                                                        ch.close();
                                                    }
                                                }
                                            });
                                }
                            }
                        });
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        var clientChannel = SocketChannel.open();
        clientChannel.connect(new InetSocketAddress("localhost", port));
        var buf = ByteBuffer.allocate(4);
        buf.putInt(42);
        buf.flip();
        clientChannel.write(buf);

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(42, received.get());

        clientChannel.close();
        serverChannel.close();
        loop.close();
    }

    @Test
    void udp_sendAndReceive() throws Exception {
        var loop = new EventLoop("test-loop");
        loop.start();

        var received = new AtomicReference<Integer>();
        var done = new CountDownLatch(1);

        var serverChannel = DatagramChannel.open();
        serverChannel.bind(new InetSocketAddress(0));
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        loop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(loop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isReadable()) {
                                var buf = ByteBuffer.allocate(64);
                                var ch = (DatagramChannel) key.channel();
                                ch.receive(buf);
                                buf.flip();
                                received.set(buf.getInt());
                                done.countDown();
                            }
                        });
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        var clientChannel = DatagramChannel.open();
        var buf = ByteBuffer.allocate(4);
        buf.putInt(99);
        buf.flip();
        clientChannel.send(buf, new InetSocketAddress("localhost", port));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(99, received.get());

        clientChannel.close();
        serverChannel.close();
        loop.close();
    }

    @Test
    void transport_factoryMethods() {
        var tcp = Transport.tcp(8080);
        assertTrue(tcp.isTcp());
        assertFalse(tcp.isUdp());
        assertEquals(8080, tcp.address().getPort());

        var udp = Transport.udp("localhost", 9090);
        assertFalse(udp.isTcp());
        assertTrue(udp.isUdp());
        assertEquals(9090, udp.address().getPort());
    }
}
