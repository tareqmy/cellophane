package io.cellophane.server.smpp;

import io.cellophane.server.account.AccountRegistry;
import io.cellophane.server.message.Inbox;
import io.cellophane.smpp.codec.SmppPipeline;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** The Netty listener that plays the mobile operator. Started and stopped with the Spring context. */
public final class SmppServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SmppServer.class);

    private final int configuredPort;
    private final String systemId;
    private final AccountRegistry accounts;
    private final SessionRegistry sessions;
    private final Inbox inbox;

    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel listener;
    private volatile int port = -1;

    public SmppServer(int port, String systemId, AccountRegistry accounts, SessionRegistry sessions, Inbox inbox) {
        this.configuredPort = port;
        this.systemId = systemId;
        this.accounts = accounts;
        this.sessions = sessions;
        this.inbox = inbox;
    }

    @Override
    public synchronized void start() {
        if (listener != null) {
            return;
        }
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workers = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        SmppPipeline.install(ch.pipeline());
                        ch.pipeline().addAfter(SmppPipeline.FRAME_DECODER, "raw-pdu-capture", new RawPduCapture());
                        ch.pipeline().addLast("smpp-session", new SmppSessionHandler(systemId, accounts, sessions,
                                inbox));
                    }
                });
        listener = bootstrap.bind(configuredPort).syncUninterruptibly().channel();
        port = ((InetSocketAddress) listener.localAddress()).getPort();
        log.info("SMPP 3.4 listening on port {} as system_id '{}' with {} account(s)", port, systemId,
                accounts.all().size());
    }

    @Override
    public synchronized void stop() {
        if (listener == null) {
            return;
        }
        listener.close().syncUninterruptibly();
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        listener = null;
        port = -1;
        log.info("SMPP listener stopped");
    }

    @Override
    public boolean isRunning() {
        return listener != null;
    }

    /** The bound port, useful when configured as 0. */
    public int port() {
        return port;
    }
}
