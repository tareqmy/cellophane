package io.cellophane.server.smpp;

import io.cellophane.server.account.Account;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One TCP connection from an ESME and, once it has bound, who it is and what it may do. */
public final class SmppSession {

    private final String id;
    private final Channel channel;
    private final Instant connectedAt;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Account account;
    private volatile BindType bindType;
    private volatile Instant boundAt;

    SmppSession(String id, Channel channel, Instant connectedAt) {
        this.id = id;
        this.channel = channel;
        this.connectedAt = connectedAt;
    }

    public String id() {
        return id;
    }

    public Channel channel() {
        return channel;
    }

    public String remoteAddress() {
        return channel.remoteAddress() instanceof InetSocketAddress a
                ? a.getAddress().getHostAddress() + ":" + a.getPort()
                : String.valueOf(channel.remoteAddress());
    }

    void bind(Account account, BindType bindType, Instant at) {
        this.account = account;
        this.bindType = bindType;
        this.boundAt = at;
    }

    public boolean isBound() {
        return bindType != null;
    }

    public Optional<Account> account() {
        return Optional.ofNullable(account);
    }

    public Optional<BindType> bindType() {
        return Optional.ofNullable(bindType);
    }

    public boolean canTransmit() {
        BindType type = bindType;
        return type != null && type.canTransmit();
    }

    public boolean canReceive() {
        BindType type = bindType;
        return type != null && type.canReceive();
    }

    /** Next sequence number for a PDU the SMSC originates on this session. */
    public int nextSequence() {
        return sequence.incrementAndGet();
    }

    void countSubmit() {
        submitted.incrementAndGet();
    }

    public Info info() {
        return new Info(id, remoteAddress(), account == null ? null : account.systemId(), bindType, connectedAt,
                boundAt, submitted.get());
    }

    /** Immutable snapshot for the API. */
    public record Info(String id, String remoteAddress, String account, BindType bindType, Instant connectedAt,
                       Instant boundAt, long submitted) {
    }
}
