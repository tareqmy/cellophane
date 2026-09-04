package io.cellophane.smpp.interop;

import io.cellophane.smpp.codec.PduException;
import io.cellophane.smpp.codec.SmppPipeline;
import io.cellophane.smpp.pdu.Bind;
import io.cellophane.smpp.pdu.EnquireLink;
import io.cellophane.smpp.pdu.Pdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Unbind;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A minimal SMSC built on the codec: answers every request, records what it saw, and can push PDUs to the ESME. */
final class TestSmsc implements AutoCloseable {

    static final String SYSTEM_ID = "cellophane";
    static final String MESSAGE_ID = "42";

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final BlockingQueue<Pdu> received = new LinkedBlockingQueue<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Channel esme;
    private Channel server;

    int start() throws InterruptedException {
        server = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        SmppPipeline.install(ch.pipeline());
                        ch.pipeline().addLast(new Handler());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel();
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    Pdu next() throws InterruptedException {
        Pdu pdu = received.poll(5, TimeUnit.SECONDS);
        if (pdu == null) {
            throw new AssertionError("no PDU received within 5s");
        }
        return pdu;
    }

    <T extends Pdu> T next(Class<T> type) throws InterruptedException {
        Pdu pdu = next();
        if (!type.isInstance(pdu)) {
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + pdu);
        }
        return type.cast(pdu);
    }

    int nextSequence() {
        return sequence.incrementAndGet();
    }

    void send(Pdu pdu) throws InterruptedException {
        esme.writeAndFlush(pdu).sync();
    }

    @Override
    public void close() {
        if (server != null) {
            server.close();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    private final class Handler extends SimpleChannelInboundHandler<Pdu> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            esme = ctx.channel();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Pdu pdu) {
            received.add(pdu);
            switch (pdu) {
                case Bind b -> ctx.writeAndFlush(b.respond(0, SYSTEM_ID));
                case SubmitSm s -> ctx.writeAndFlush(s.respond(0, MESSAGE_ID));
                case EnquireLink e -> ctx.writeAndFlush(e.respond());
                case Unbind u -> ctx.writeAndFlush(u.respond(0)).addListener(ChannelFutureListener.CLOSE);
                default -> { }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof PduException e) {
                ctx.writeAndFlush(e.toNack()).addListener(ChannelFutureListener.CLOSE);
            } else {
                ctx.close();
            }
        }
    }
}
