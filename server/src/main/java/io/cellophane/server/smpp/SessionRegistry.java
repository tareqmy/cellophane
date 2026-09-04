package io.cellophane.server.smpp;

import io.cellophane.server.account.Account;
import io.netty.channel.Channel;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Live ESME sessions. */
public final class SessionRegistry {

    private final ConcurrentMap<String, SmppSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final Clock clock;

    public SessionRegistry(Clock clock) {
        this.clock = clock;
    }

    SmppSession open(Channel channel) {
        SmppSession session = new SmppSession("s" + counter.incrementAndGet(), channel, clock.instant());
        sessions.put(session.id(), session);
        return session;
    }

    void bind(SmppSession session, Account account, BindType type) {
        session.bind(account, type, clock.instant());
    }

    void close(SmppSession session) {
        sessions.remove(session.id());
    }

    public Optional<SmppSession> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public List<SmppSession> all() {
        return List.copyOf(sessions.values());
    }

    public List<SmppSession> bound() {
        return sessions.values().stream().filter(SmppSession::isBound).toList();
    }
}
