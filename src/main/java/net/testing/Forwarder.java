package net.testing;

import net.transport.Transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Forwarder implements AutoCloseable {

    private record ForwardConfig(
            Transport listenTransport,
            String targetHost,
            int targetPort,
            Duration latencyMin,
            Duration latencyMax,
            double packetLoss,
            double reorderRate
    ) {}

    private final List<ForwardConfig> forwards;
    private final List<Integer> boundPorts = new ArrayList<>();
    private final List<ServerSocketChannel> serverChannels = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Thread acceptThread;
    private volatile boolean running;
    private Selector selector;
    private final Random rng = new Random();
    private Forwarder(List<ForwardConfig> forwards) {
        this.forwards = forwards;
        this.acceptThread = new Thread(this::runLoop, "forwarder");
        this.acceptThread.setDaemon(true);
    }

    public void start() throws IOException {
        selector = Selector.open();
        running = true;

        for (var fwd : forwards) {
            if (fwd.listenTransport().isTcp()) {
                var serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.bind(fwd.listenTransport().address());
                int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
                boundPorts.add(port);
                serverChannels.add(serverChannel);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT, fwd);
            } else {
                // UDP forwarder
                var listenChannel = java.nio.channels.DatagramChannel.open();
                listenChannel.configureBlocking(false);
                listenChannel.bind(fwd.listenTransport().address());
                int port = ((InetSocketAddress) listenChannel.getLocalAddress()).getPort();
                boundPorts.add(port);

                var targetAddr = new InetSocketAddress(fwd.targetHost(), fwd.targetPort());
                var targetChannel = java.nio.channels.DatagramChannel.open();
                targetChannel.configureBlocking(false);

                var readBuf = ByteBuffer.allocate(65536);
                var udpState = new UdpForwardState(listenChannel, targetChannel, targetAddr, fwd, readBuf);
                listenChannel.register(selector, SelectionKey.OP_READ, udpState);
            }
        }

        acceptThread.start();
    }

    private void runLoop() {
        while (running) {
            try {
                selector.select(100);
                var keys = selector.selectedKeys();
                var iter = keys.iterator();
                while (iter.hasNext()) {
                    var key = iter.next();
                    iter.remove();
                    if (!key.isValid()) continue;
                    if (key.isAcceptable() && key.attachment() instanceof ForwardConfig fwd) {
                        handleAccept(key, fwd);
                    } else if (key.isReadable() && key.attachment() instanceof PipeState pipe) {
                        handleRead(key, pipe);
                    } else if (key.isReadable() && key.attachment() instanceof UdpForwardState udp) {
                        handleUdpRead(udp);
                    }
                }
            } catch (IOException e) {
                if (running) throw new RuntimeException("Forwarder error", e);
            }
        }
    }

    private record UdpForwardState(
            java.nio.channels.DatagramChannel listenChannel,
            java.nio.channels.DatagramChannel targetChannel,
            InetSocketAddress targetAddr,
            ForwardConfig config,
            ByteBuffer readBuf
    ) {}

    private void handleUdpRead(UdpForwardState state) throws IOException {
        state.readBuf().clear();
        var sender = state.listenChannel().receive(state.readBuf());
        if (sender == null) return;
        state.readBuf().flip();

        var data = new byte[state.readBuf().remaining()];
        state.readBuf().get(data);

        // Apply impairments
        if (state.config().packetLoss() > 0 && rng.nextDouble() < state.config().packetLoss()) {
            return; // drop
        }

        long delayMs = 0;
        if (state.config().latencyMin() != null && state.config().latencyMax() != null) {
            long min = state.config().latencyMin().toMillis();
            long max = state.config().latencyMax().toMillis();
            delayMs = min + (long) (rng.nextDouble() * (max - min));
        }

        if (delayMs > 0) {
            scheduler.schedule(() -> {
                try {
                    state.targetChannel().send(ByteBuffer.wrap(data), state.targetAddr());
                } catch (IOException _) {}
            }, delayMs, TimeUnit.MILLISECONDS);
        } else {
            state.targetChannel().send(ByteBuffer.wrap(data), state.targetAddr());
        }
    }

    private record PipeState(
            SocketChannel source,
            SocketChannel target,
            ForwardConfig config,
            ByteBuffer readBuf
    ) {}

    private void handleAccept(SelectionKey key, ForwardConfig fwd) throws IOException {
        var serverChannel = (ServerSocketChannel) key.channel();
        var clientChannel = serverChannel.accept();
        if (clientChannel == null) return;
        clientChannel.configureBlocking(false);

        // Blocking connect to target (localhost = fast)
        var targetChannel = SocketChannel.open();
        targetChannel.connect(new InetSocketAddress(fwd.targetHost(), fwd.targetPort()));
        targetChannel.configureBlocking(false);

        // Bidirectional forwarding
        var clientToTarget = new PipeState(clientChannel, targetChannel, fwd, ByteBuffer.allocate(65536));
        var targetToClient = new PipeState(targetChannel, clientChannel, fwd, ByteBuffer.allocate(65536));

        clientChannel.register(selector, SelectionKey.OP_READ, clientToTarget);
        targetChannel.register(selector, SelectionKey.OP_READ, targetToClient);
    }

    private void handleRead(SelectionKey key, PipeState pipe) throws IOException {
        var buf = pipe.readBuf();
        int n = pipe.source().read(buf);
        if (n == -1) {
            key.cancel();
            pipe.source().close();
            pipe.target().close();
            return;
        }
        if (n > 0) {
            buf.flip();
            var data = new byte[buf.remaining()];
            buf.get(data);
            buf.clear();

            // Apply impairments
            if (pipe.config().packetLoss() > 0 && rng.nextDouble() < pipe.config().packetLoss()) {
                // Drop this packet
                return;
            }

            long delayMs = 0;
            if (pipe.config().latencyMin() != null && pipe.config().latencyMax() != null) {
                long min = pipe.config().latencyMin().toMillis();
                long max = pipe.config().latencyMax().toMillis();
                delayMs = min + (long) (rng.nextDouble() * (max - min));
            }

            if (delayMs > 0) {
                var target = pipe.target();
                scheduler.schedule(() -> {
                    try {
                        target.write(ByteBuffer.wrap(data));
                    } catch (IOException _) {}
                }, delayMs, TimeUnit.MILLISECONDS);
            } else {
                pipe.target().write(ByteBuffer.wrap(data));
            }
        }
    }

    public int port(int index) {
        return boundPorts.get(index);
    }

    @Override
    public void close() {
        running = false;
        if (selector != null) selector.wakeup();
        try { acceptThread.join(2000); } catch (InterruptedException _) {}
        for (var ch : serverChannels) {
            try { ch.close(); } catch (IOException _) {}
        }
        scheduler.shutdownNow();
        try { selector.close(); } catch (IOException _) {}
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ForwardConfig> forwards = new ArrayList<>();
        private Transport currentListenTransport;
        private String currentTargetHost;
        private int currentTargetPort;
        private Duration latencyMin;
        private Duration latencyMax;
        private double packetLoss;
        private double reorderRate;

        public Builder forward(Transport listenTransport, String targetHost, int targetPort) {
            flushCurrent();
            this.currentListenTransport = listenTransport;
            this.currentTargetHost = targetHost;
            this.currentTargetPort = targetPort;
            this.latencyMin = null;
            this.latencyMax = null;
            this.packetLoss = 0;
            this.reorderRate = 0;
            return this;
        }

        public Builder latency(Duration min, Duration max) {
            this.latencyMin = min;
            this.latencyMax = max;
            return this;
        }

        public Builder packetLoss(double rate) {
            this.packetLoss = rate;
            return this;
        }

        public Builder reorder(double rate) {
            this.reorderRate = rate;
            return this;
        }

        private void flushCurrent() {
            if (currentListenTransport != null) {
                forwards.add(new ForwardConfig(
                        currentListenTransport, currentTargetHost, currentTargetPort,
                        latencyMin, latencyMax, packetLoss, reorderRate));
            }
        }

        public Forwarder build() {
            flushCurrent();
            return new Forwarder(List.copyOf(forwards));
        }
    }
}
