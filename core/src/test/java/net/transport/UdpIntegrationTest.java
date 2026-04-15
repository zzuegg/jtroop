package net.transport;

import net.client.Client;
import net.pipeline.layers.FramingLayer;
import net.server.Server;
import net.service.*;
import net.session.ConnectionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class UdpIntegrationTest {

    record GameConn(int v) {}

    record ReliableCmd(int id) {}
    record FastUpdate(float x, float y) {}

    interface GameService {
        void command(ReliableCmd cmd);
        @Datagram void update(FastUpdate u);
    }

    @Handles(GameService.class)
    static class GameHandler {
        final CopyOnWriteArrayList<ReliableCmd> commands = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<FastUpdate> updates = new CopyOnWriteArrayList<>();
        CountDownLatch cmdLatch = new CountDownLatch(1);
        CountDownLatch updateLatch = new CountDownLatch(1);

        @OnMessage void command(ReliableCmd cmd, ConnectionId s) {
            commands.add(cmd); cmdLatch.countDown();
        }
        @OnMessage void update(FastUpdate u, ConnectionId s) {
            updates.add(u); updateLatch.countDown();
        }
    }

    @Test
    void server_listensOnUdp_clientSendsDatagrams() throws Exception {
        var handler = new GameHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.udp(0))
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int udpPort = server.udpPort(GameConn.class);

        var client = Client.builder()
                .connect(GameConn.class, Transport.udp("localhost", udpPort))
                .addService(GameService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        client.send(new FastUpdate(1.0f, 2.0f));
        assertTrue(handler.updateLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1.0f, handler.updates.getFirst().x());

        client.close();
        server.close();
    }

    @Test
    void server_tcpAndUdp_sameGroup_datagramRoutesToUdp() throws Exception {
        var handler = new GameHandler();
        var server = Server.builder()
                .listen(GameConn.class, Transport.tcp(0), new FramingLayer())
                .listen(GameConn.class, Transport.udp(0))
                .addService(handler, GameConn.class)
                .build();
        server.start();
        int tcpPort = server.port(GameConn.class);
        int udpPort = server.udpPort(GameConn.class);

        var client = Client.builder()
                .connect(GameConn.class, Transport.tcp("localhost", tcpPort), new FramingLayer())
                .connect(GameConn.class, Transport.udp("localhost", udpPort))
                .addService(GameService.class, GameConn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Non-datagram → TCP
        client.send(new ReliableCmd(1));
        assertTrue(handler.cmdLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, handler.commands.size());

        // @Datagram → UDP
        client.send(new FastUpdate(3.0f, 4.0f));
        assertTrue(handler.updateLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, handler.updates.size());

        client.close();
        server.close();
    }
}
