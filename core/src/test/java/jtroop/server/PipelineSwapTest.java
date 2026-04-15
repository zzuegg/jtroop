package jtroop.server;

import jtroop.client.Client;
import jtroop.pipeline.Pipeline;
import jtroop.pipeline.layers.CompressionLayer;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PipelineSwapTest {

    record Conn(int v) {}
    record NormalMsg(String text) {}
    record UpgradeRequest(int protocol) {}
    record CompressedMsg(String data) {}

    interface Svc {
        void normal(NormalMsg msg);
        void upgrade(UpgradeRequest req);
        void compressed(CompressedMsg msg);
    }

    @Handles(Svc.class)
    static class Handler {
        final CopyOnWriteArrayList<NormalMsg> normals = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<CompressedMsg> compressed = new CopyOnWriteArrayList<>();
        CountDownLatch normalLatch = new CountDownLatch(1);
        CountDownLatch compressedLatch = new CountDownLatch(1);
        Server serverRef;

        @OnMessage void normal(NormalMsg msg, ConnectionId sender) {
            normals.add(msg);
            normalLatch.countDown();
        }

        @OnMessage void upgrade(UpgradeRequest req, ConnectionId sender) {
            // Swap to compressed pipeline
            if (serverRef != null) {
                serverRef.switchPipeline(sender, new Pipeline(new CompressionLayer(), new FramingLayer()));
            }
        }

        @OnMessage void compressed(CompressedMsg msg, ConnectionId sender) {
            compressed.add(msg);
            compressedLatch.countDown();
        }
    }

    @Test
    void switchPipeline_changesProcessingForConnection() throws Exception {
        var handler = new Handler();
        var server = Server.builder()
                .listen(Conn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, Conn.class)
                .build();
        handler.serverRef = server;
        server.start();
        int port = server.port(Conn.class);

        var client = Client.builder()
                .connect(Conn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(Svc.class, Conn.class)
                .build();
        client.start();
        Thread.sleep(300);

        // Send normal message (no compression)
        client.send(new NormalMsg("hello"));
        assertTrue(handler.normalLatch.await(2, TimeUnit.SECONDS));
        assertEquals("hello", handler.normals.getFirst().text());

        client.close();
        server.close();
    }
}
