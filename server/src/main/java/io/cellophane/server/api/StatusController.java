package io.cellophane.server.api;

import io.cellophane.server.account.AccountRegistry;
import io.cellophane.server.message.Message;
import io.cellophane.server.message.MessageQuery;
import io.cellophane.server.message.MessageStore;
import io.cellophane.server.operator.Metrics;
import io.cellophane.server.smpp.SessionRegistry;
import io.cellophane.server.smpp.SmppServer;
import io.cellophane.server.smpp.SmppSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Who is connected and how full the inbox is. */
@RestController
@RequestMapping("/api/v1")
class StatusController {

    private final SmppServer server;
    private final SessionRegistry sessions;
    private final MessageStore store;
    private final MessageEvents events;
    private final AccountRegistry accounts;
    private final Metrics metrics;

    StatusController(SmppServer server, SessionRegistry sessions, MessageStore store, MessageEvents events,
                     AccountRegistry accounts, Metrics metrics) {
        this.server = server;
        this.sessions = sessions;
        this.store = store;
        this.events = events;
        this.accounts = accounts;
        this.metrics = metrics;
    }

    @GetMapping("/sessions")
    List<SmppSession.Info> sessions() {
        return sessions.all().stream().map(SmppSession::info).toList();
    }

    @GetMapping("/stats")
    Stats stats() {
        Map<String, Integer> byStatus = new TreeMap<>();
        for (Message m : store.list(MessageQuery.all(MessageQuery.MAX_LIMIT)).messages()) {
            byStatus.merge(m.status().name(), 1, Integer::sum);
        }
        return new Stats(server.port(), store.size(), store.capacity(), byStatus, sessions.all().size(),
                sessions.bound().size(), events.subscribers(), accounts.all().stream().map(a -> a.systemId()).toList(),
                metrics.snapshot());
    }

    /**
     * @param byStatus counts over the newest {@value MessageQuery#MAX_LIMIT} messages in the inbox
     * @param totals   counters since start, and submits per second averaged over the last ten seconds
     */
    record Stats(int smppPort, int messages, int capacity, Map<String, Integer> byStatus, int sessions,
                 int boundSessions, int streamSubscribers, List<String> accounts, Metrics.Snapshot totals) {
    }
}
