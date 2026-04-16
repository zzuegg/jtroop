package bench.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * Standalone Netty HTTP server for wrk comparison.
 * Returns "Hello, World!" for every request (same as jtroop HttpServerMain).
 */
public class NettyHttpServerMain {

    private static final byte[] HELLO = "Hello, World!".getBytes(CharsetUtil.UTF_8);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8766;
        var bossGroup = new NioEventLoopGroup(1);
        var workerGroup = new NioEventLoopGroup();
        try {
            var b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(
                             new HttpServerCodec(),
                             new HttpObjectAggregator(65536),
                             new HelloHandler()
                     );
                 }
             });
            var ch = b.bind(port).sync().channel();
            System.out.println("Netty HTTP server listening on port " + port);
            System.out.println("Test with: wrk -t2 -c50 -d10s http://localhost:" + port + "/");
            ch.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    static class HelloHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            var resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(HELLO));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, HELLO.length);
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(resp);
        }
    }
}
