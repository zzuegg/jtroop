package bench.netty;

import bench.GameMessages;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: Netty game server with proper object encoding/decoding.
 * Client sends typed message objects through an encoder pipeline.
 * Server decodes bytes into typed objects and dispatches.
 * This matches what jtroop does: record → codec → pipeline → wire → decode → dispatch.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class NettyGameBenchmark {

    // Message types — equivalent to jtroop records
    public record PositionUpdate(float x, float y, float z, float yaw) {}
    public record ChatMessage(String text, int room) {}

    // Encoder: object → bytes (equivalent to jtroop codec encode)
    static class GameMessageEncoder extends MessageToByteEncoder<Object> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
            switch (msg) {
                case PositionUpdate p -> {
                    out.writeShort(GameMessages.MSG_POSITION_UPDATE);
                    out.writeFloat(p.x());
                    out.writeFloat(p.y());
                    out.writeFloat(p.z());
                    out.writeFloat(p.yaw());
                }
                case ChatMessage c -> {
                    out.writeShort(GameMessages.MSG_CHAT);
                    var bytes = c.text().getBytes(StandardCharsets.UTF_8);
                    out.writeInt(bytes.length);
                    out.writeBytes(bytes);
                    out.writeInt(c.room());
                }
                default -> throw new IllegalArgumentException("Unknown message: " + msg);
            }
        }
    }

    // Decoder: bytes → object (equivalent to jtroop codec decode)
    static class GameMessageDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 2) return;
            in.markReaderIndex();
            int type = in.readShort();
            switch (type) {
                case GameMessages.MSG_POSITION_UPDATE -> {
                    if (in.readableBytes() < 16) { in.resetReaderIndex(); return; }
                    out.add(new PositionUpdate(in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readFloat()));
                }
                case GameMessages.MSG_CHAT -> {
                    if (in.readableBytes() < 4) { in.resetReaderIndex(); return; }
                    int len = in.readInt();
                    if (in.readableBytes() < len + 4) { in.resetReaderIndex(); return; }
                    var bytes = new byte[len];
                    in.readBytes(bytes);
                    int room = in.readInt();
                    out.add(new ChatMessage(new String(bytes, StandardCharsets.UTF_8), room));
                }
                default -> in.resetReaderIndex();
            }
        }
    }

    // Server handler: receives typed objects (equivalent to jtroop @OnMessage handler)
    static class ServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            switch (msg) {
                case PositionUpdate p -> { /* process position */ }
                case ChatMessage c -> { /* process chat */ }
                default -> {}
            }
        }
    }

    static class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) { }
    }

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventLoopGroup clientGroup;
    private Channel serverChannel;
    private Channel clientChannel;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        clientGroup = new NioEventLoopGroup(1);

        var serverBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
                                new LengthFieldPrepender(4),
                                new GameMessageDecoder(),
                                new ServerHandler()
                        );
                    }
                });

        var bindFuture = serverBootstrap.bind(0).sync();
        serverChannel = bindFuture.channel();

        var clientBootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
                                new LengthFieldPrepender(4),
                                new GameMessageEncoder(),
                                new ClientHandler()
                        );
                    }
                });

        clientChannel = clientBootstrap.connect("localhost",
                ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort()).sync().channel();
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        if (clientChannel != null) clientChannel.close().sync();
        if (serverChannel != null) serverChannel.close().sync();
        clientGroup.shutdownGracefully().sync();
        workerGroup.shutdownGracefully().sync();
        bossGroup.shutdownGracefully().sync();
    }

    // --- Fire-and-forget benchmarks ---

    @Benchmark
    public void positionUpdate() {
        clientChannel.writeAndFlush(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f));
    }

    @Benchmark
    public void chatMessage() {
        clientChannel.writeAndFlush(new ChatMessage(GameMessages.CHAT_TEXT, 1));
    }

    @Benchmark
    public void mixedTraffic() {
        for (int i = 0; i < 10; i++) {
            if (i < 8) {
                clientChannel.writeAndFlush(
                        new PositionUpdate(i * 0.1f, i * 0.2f, i * 0.3f, i * 0.01f));
            } else {
                clientChannel.writeAndFlush(new ChatMessage(GameMessages.CHAT_TEXT, i));
            }
        }
    }

    // --- Blocking benchmarks (comparable to jtroop sendBlocking) ---

    @Benchmark
    public void positionUpdate_blocking() throws Exception {
        clientChannel.writeAndFlush(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f)).sync();
    }

    @Benchmark
    public void chatMessage_blocking() throws Exception {
        clientChannel.writeAndFlush(new ChatMessage(GameMessages.CHAT_TEXT, 1)).sync();
    }
}
