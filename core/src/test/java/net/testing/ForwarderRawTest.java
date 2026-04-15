package net.testing;

import net.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ForwarderRawTest {

    @Test
    void rawTcpRelay_bidirectional() throws Exception {
        // Start a simple echo server
        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(0));
        int serverPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        var serverThread = new Thread(() -> {
            try {
                var client = serverChannel.accept();
                var buf = ByteBuffer.allocate(256);
                int n = client.read(buf);
                buf.flip();
                // Echo back
                client.write(buf);
                client.close();
            } catch (Exception e) { e.printStackTrace(); }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Start forwarder
        var forwarder = Forwarder.builder()
                .forward(Transport.tcp(0), "localhost", serverPort)
                .build();
        forwarder.start();
        int fwdPort = forwarder.port(0);

        Thread.sleep(200);

        // Connect through forwarder
        var client = SocketChannel.open();
        client.connect(new InetSocketAddress("localhost", fwdPort));

        // Send data
        var sendBuf = ByteBuffer.allocate(4);
        sendBuf.putInt(42);
        sendBuf.flip();
        client.write(sendBuf);

        // Read echo response
        var recvBuf = ByteBuffer.allocate(4);
        int totalRead = 0;
        long deadline = System.currentTimeMillis() + 3000;
        while (totalRead < 4 && System.currentTimeMillis() < deadline) {
            int n = client.read(recvBuf);
            if (n > 0) totalRead += n;
            else Thread.sleep(10);
        }
        recvBuf.flip();
        assertEquals(4, totalRead, "Expected 4 bytes back");
        assertEquals(42, recvBuf.getInt());

        client.close();
        forwarder.close();
        serverChannel.close();
    }
}
