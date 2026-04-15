package net.server;

import net.codec.CodecRegistry;
import net.core.EventLoop;
import net.core.ReadBuffer;
import net.core.WriteBuffer;
import net.pipeline.Layer;
import net.pipeline.Pipeline;
import net.service.ServiceRegistry;
import net.session.ConnectionId;
import net.session.SessionStore;
import net.transport.Transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public final class Server implements AutoCloseable {

    private record ListenerConfig(
            Class<? extends Record> connectionType,
            Transport transport,
            Pipeline pipeline,
            Layer[] layers
    ) {}

    private record ServiceBinding(
            Object handlerInstance,
            Class<? extends Record> connectionType
    ) {}

    private final List<ListenerConfig> listeners;
    private final ServiceRegistry serviceRegistry;
    private final CodecRegistry codec;
    private final SessionStore sessions;
    private final EventLoop eventLoop;
    private final Map<Class<? extends Record>, Integer> boundPorts = new HashMap<>();
    private final Map<SelectionKey, ConnectionId> keyToConnection = new HashMap<>();
    private final Map<ConnectionId, SelectionKey> connectionToKey = new HashMap<>();

    private Server(List<ListenerConfig> listeners, ServiceRegistry serviceRegistry,
                   CodecRegistry codec) {
        this.listeners = listeners;
        this.serviceRegistry = serviceRegistry;
        this.codec = codec;
        this.sessions = new SessionStore(4096);
        try {
            this.eventLoop = new EventLoop("server-loop");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create event loop", e);
        }
    }

    public void start() throws IOException {
        eventLoop.start();
        for (var listener : listeners) {
            if (listener.transport().isTcp()) {
                startTcpListener(listener);
            }
        }
    }

    private void startTcpListener(ListenerConfig config) throws IOException {
        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(config.transport().address());
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        boundPorts.put(config.connectionType(), port);

        eventLoop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(eventLoop.selector(), SelectionKey.OP_ACCEPT,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isAcceptable()) {
                                acceptClient(serverChannel, config);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to register server socket", e);
            }
        });
    }

    private void acceptClient(ServerSocketChannel serverChannel, ListenerConfig config) throws IOException {
        var clientChannel = serverChannel.accept();
        if (clientChannel == null) return;
        clientChannel.configureBlocking(false);
        var connId = sessions.allocate();
        var readBuf = ByteBuffer.allocate(65536);

        var selKey = clientChannel.register(eventLoop.selector(), SelectionKey.OP_READ,
                (EventLoop.KeyHandler) key -> handleRead(key, connId, config, readBuf));
        keyToConnection.put(selKey, connId);
        connectionToKey.put(connId, selKey);
    }

    private void handleRead(SelectionKey key, ConnectionId connId, ListenerConfig config,
                            ByteBuffer readBuf) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n = channel.read(readBuf);
        if (n == -1) {
            // Client disconnected
            key.cancel();
            channel.close();
            sessions.release(connId);
            keyToConnection.remove(key);
            connectionToKey.remove(connId);
            return;
        }
        if (n > 0) {
            readBuf.flip();
            processInbound(readBuf, connId, config, channel);
            readBuf.compact();
        }
    }

    private void processInbound(ByteBuffer wire, ConnectionId sender, ListenerConfig config,
                                SocketChannel channel) {
        // Decode through pipeline
        var frame = config.pipeline().decodeInbound(wire);
        while (frame != null) {
            var rb = new ReadBuffer(frame);
            var message = codec.decode(rb);

            // Dispatch to handler
            var result = serviceRegistry.dispatch(message, sender);

            // If there's a response, send it back
            if (result instanceof Record response) {
                sendResponse(response, config, channel);
            }

            frame = config.pipeline().decodeInbound(wire);
        }
    }

    private void sendResponse(Record response, ListenerConfig config, SocketChannel channel) {
        var buf = ByteBuffer.allocate(65536);
        var wb = new WriteBuffer(buf);
        codec.encode(response, wb);
        buf.flip();

        var wire = ByteBuffer.allocate(65536);
        config.pipeline().encodeOutbound(buf, wire);
        wire.flip();

        try {
            channel.write(wire);
        } catch (IOException e) {
            // Connection lost
        }
    }

    public int port(Class<? extends Record> connectionType) {
        var p = boundPorts.get(connectionType);
        if (p == null) throw new IllegalArgumentException("No listener for " + connectionType.getName());
        return p;
    }

    public boolean isRunning() {
        return eventLoop.isRunning();
    }

    @Override
    public void close() {
        eventLoop.close();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ListenerConfig> listeners = new ArrayList<>();
        private final List<ServiceBinding> services = new ArrayList<>();
        private final CodecRegistry codec = new CodecRegistry();
        private final ServiceRegistry serviceRegistry = new ServiceRegistry(codec);

        public Builder listen(Class<? extends Record> connectionType, Transport transport, Layer... layers) {
            listeners.add(new ListenerConfig(connectionType, transport, new Pipeline(layers), layers));
            return this;
        }

        public Builder addService(Class<?> handlerClass, Class<? extends Record> connectionType) {
            serviceRegistry.register(handlerClass);
            return this;
        }

        public Builder addService(Object handlerInstance, Class<? extends Record> connectionType) {
            serviceRegistry.register(handlerInstance);
            return this;
        }

        public Server build() {
            return new Server(List.copyOf(listeners), serviceRegistry, codec);
        }
    }
}
