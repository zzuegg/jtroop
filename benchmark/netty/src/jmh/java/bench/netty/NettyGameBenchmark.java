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
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: Netty game server processing position updates + chat messages.
 * Measures throughput and GC allocation rate.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class NettyGameBenchmark {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventLoopGroup clientGroup;
    private Channel serverChannel;
    private Channel clientChannel;
    private int port;

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
                                new ServerHandler()
                        );
                    }
                });

        var bindFuture = serverBootstrap.bind(0).sync();
        serverChannel = bindFuture.channel();
        port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

        var clientBootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
                                new LengthFieldPrepender(4),
                                new ClientHandler()
                        );
                    }
                });

        clientChannel = clientBootstrap.connect("localhost", port).sync().channel();
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        if (clientChannel != null) clientChannel.close().sync();
        if (serverChannel != null) serverChannel.close().sync();
        clientGroup.shutdownGracefully().sync();
        workerGroup.shutdownGracefully().sync();
        bossGroup.shutdownGracefully().sync();
    }

    @Benchmark
    public void positionUpdate() throws Exception {
        var buf = clientChannel.alloc().buffer(GameMessages.POSITION_UPDATE_SIZE + 2);
        buf.writeShort(GameMessages.MSG_POSITION_UPDATE);
        buf.writeFloat(1.0f); // x
        buf.writeFloat(2.0f); // y
        buf.writeFloat(3.0f); // z
        buf.writeFloat(0.5f); // yaw
        clientChannel.writeAndFlush(buf).sync();
    }

    @Benchmark
    public void chatMessage() throws Exception {
        var text = GameMessages.CHAT_TEXT;
        var textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var buf = clientChannel.alloc().buffer(2 + 4 + textBytes.length + 4);
        buf.writeShort(GameMessages.MSG_CHAT);
        buf.writeInt(textBytes.length);
        buf.writeBytes(textBytes);
        buf.writeInt(1); // room
        clientChannel.writeAndFlush(buf).sync();
    }

    @Benchmark
    public void mixedTraffic() throws Exception {
        // 80% position updates, 20% chat (typical game workload)
        for (int i = 0; i < 10; i++) {
            if (i < 8) {
                var buf = clientChannel.alloc().buffer(GameMessages.POSITION_UPDATE_SIZE + 2);
                buf.writeShort(GameMessages.MSG_POSITION_UPDATE);
                buf.writeFloat(i * 0.1f);
                buf.writeFloat(i * 0.2f);
                buf.writeFloat(i * 0.3f);
                buf.writeFloat(i * 0.01f);
                clientChannel.writeAndFlush(buf);
            } else {
                var textBytes = GameMessages.CHAT_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var buf = clientChannel.alloc().buffer(2 + 4 + textBytes.length + 4);
                buf.writeShort(GameMessages.MSG_CHAT);
                buf.writeInt(textBytes.length);
                buf.writeBytes(textBytes);
                buf.writeInt(i);
                clientChannel.writeAndFlush(buf);
            }
        }
        clientChannel.flush();
    }

    static class ServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            var buf = (ByteBuf) msg;
            int msgType = buf.readShort();
            switch (msgType) {
                case GameMessages.MSG_POSITION_UPDATE -> {
                    float x = buf.readFloat();
                    float y = buf.readFloat();
                    float z = buf.readFloat();
                    float yaw = buf.readFloat();
                    // Process position — in real game: update world state
                }
                case GameMessages.MSG_CHAT -> {
                    int textLen = buf.readInt();
                    var textBytes = new byte[textLen];
                    buf.readBytes(textBytes);
                    int room = buf.readInt();
                    // Process chat — in real game: broadcast
                }
            }
            buf.release();
        }
    }

    static class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ((ByteBuf) msg).release();
        }
    }
}
