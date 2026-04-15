package net.client;

import net.codec.CodecRegistry;
import net.core.EventLoop;
import net.core.ReadBuffer;
import net.core.WriteBuffer;
import net.pipeline.Layer;
import net.pipeline.Pipeline;
import net.service.ServiceRegistry;
import net.transport.Transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class Client implements AutoCloseable {

    private record ConnectionConfig(
            Class<? extends Record> connectionType,
            Transport transport,
            Pipeline pipeline,
            Layer[] layers
    ) {}

    private final List<ConnectionConfig> connections;
    private final CodecRegistry codec;
    private final Map<Class<? extends Record>, Class<? extends Record>> serviceToConnection;
    private final EventLoop eventLoop;
    private final Map<Class<? extends Record>, SocketChannel> channels = new HashMap<>();
    private final Map<Class<? extends Record>, ConnectionConfig> configByType = new HashMap<>();
    private final Map<Integer, CompletableFuture<Record>> pendingRequests = new ConcurrentHashMap<>();
    private int requestIdCounter = 0;

    private Client(List<ConnectionConfig> connections, CodecRegistry codec,
                   Map<Class<? extends Record>, Class<? extends Record>> serviceToConnection) {
        this.connections = connections;
        this.codec = codec;
        this.serviceToConnection = serviceToConnection;
        for (var conn : connections) {
            configByType.put(conn.connectionType(), conn);
        }
        try {
            this.eventLoop = new EventLoop("client-loop");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create event loop", e);
        }
    }

    public void start() throws IOException {
        eventLoop.start();
        for (var config : connections) {
            if (config.transport().isTcp()) {
                connectTcp(config);
            }
        }
    }

    private void connectTcp(ConnectionConfig config) throws IOException {
        var channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.connect(config.transport().address());
        channel.configureBlocking(false);
        channels.put(config.connectionType(), channel);

        eventLoop.submit(() -> {
            try {
                var readBuf = ByteBuffer.allocate(65536);
                channel.register(eventLoop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isReadable()) {
                                handleRead(key, config, readBuf);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("TCP connect failed", e);
            }
        });
    }

    private void handleRead(SelectionKey key, ConnectionConfig config, ByteBuffer readBuf) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n = channel.read(readBuf);
        if (n == -1) {
            key.cancel();
            channel.close();
            return;
        }
        if (n > 0) {
            readBuf.flip();
            var frame = config.pipeline().decodeInbound(readBuf);
            while (frame != null) {
                var rb = new ReadBuffer(frame);
                var message = codec.decode(rb);
                handleIncoming(message);
                frame = config.pipeline().decodeInbound(readBuf);
            }
            readBuf.compact();
        }
    }

    private void handleIncoming(Record message) {
        // Check if this is a response to a pending request
        // For now, complete any pending future that expects this type
        for (var entry : pendingRequests.entrySet()) {
            var future = entry.getValue();
            if (!future.isDone()) {
                future.complete(message);
                pendingRequests.remove(entry.getKey());
                return;
            }
        }
    }

    public void send(Record message) {
        // Find which connection this message goes through
        var connType = resolveConnection(message.getClass());
        var config = configByType.get(connType);
        var channel = channels.get(connType);
        if (channel == null || config == null) {
            throw new IllegalStateException("No connection for " + message.getClass().getName());
        }

        var buf = ByteBuffer.allocate(65536);
        var wb = new WriteBuffer(buf);
        codec.encode(message, wb);
        buf.flip();

        var wire = ByteBuffer.allocate(65536);
        config.pipeline().encodeOutbound(buf, wire);
        wire.flip();

        eventLoop.submit(() -> {
            try {
                channel.write(wire);
            } catch (IOException e) {
                throw new RuntimeException("Send failed", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T request(Record message, Class<T> responseType) {
        var future = new CompletableFuture<Record>();
        int reqId = requestIdCounter++;
        pendingRequests.put(reqId, future);
        send(message);
        try {
            return (T) future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(reqId);
            throw new RuntimeException("Request failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Record> resolveConnection(Class<?> messageType) {
        var connType = serviceToConnection.get(messageType);
        if (connType != null) return connType;
        // Fallback: use the first connection
        if (!configByType.isEmpty()) {
            return configByType.keySet().iterator().next();
        }
        throw new IllegalStateException("Cannot resolve connection for " + messageType.getName());
    }

    public boolean isConnected(Class<? extends Record> connectionType) {
        var channel = channels.get(connectionType);
        return channel != null && channel.isConnected();
    }

    @Override
    public void close() {
        for (var channel : channels.values()) {
            try { channel.close(); } catch (IOException _) {}
        }
        eventLoop.close();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ConnectionConfig> connections = new ArrayList<>();
        private final CodecRegistry codec = new CodecRegistry();
        private final Map<Class<? extends Record>, Class<? extends Record>> serviceToConnection = new HashMap<>();

        public Builder connect(Class<? extends Record> connectionType, Transport transport, Layer... layers) {
            connections.add(new ConnectionConfig(connectionType, transport, new Pipeline(layers), layers));
            return this;
        }

        public Builder addService(Class<?> serviceInterface, Class<? extends Record> connectionType) {
            // Register all message types found on the service interface methods
            for (var method : serviceInterface.getDeclaredMethods()) {
                for (var paramType : method.getParameterTypes()) {
                    if (Record.class.isAssignableFrom(paramType)) {
                        @SuppressWarnings("unchecked")
                        var recordType = (Class<? extends Record>) paramType;
                        codec.register(recordType);
                        serviceToConnection.put(recordType, connectionType);
                    }
                }
                // Register return types too
                if (Record.class.isAssignableFrom(method.getReturnType())) {
                    @SuppressWarnings("unchecked")
                    var returnType = (Class<? extends Record>) method.getReturnType();
                    codec.register(returnType);
                }
            }
            return this;
        }

        public Client build() {
            return new Client(List.copyOf(connections), codec, Map.copyOf(serviceToConnection));
        }
    }
}
