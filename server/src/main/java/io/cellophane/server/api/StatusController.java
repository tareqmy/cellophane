package io.cellophane.server.api;

import io.cellophane.server.account.AccountRegistry;
import io.cellophane.server.message.MessageStore;
import io.cellophane.server.smpp.SessionRegistry;
import io.cellophane.server.smpp.SmppServer;
import io.cellophane.server.smpp.SmppSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Who is connected and how full the inbox is. */
@RestController
@RequestMapping("/api/v1")
class StatusController {

    private final SmppServer server;
    private final SessionRegistry sessions;
    private final MessageStore store;
    private final MessageEvents events;
    private final AccountRegistry accounts;

    StatusController(SmppServer server, SessionRegistry sessions, MessageStore store, MessageEvents events,
                     AccountRegistry accounts) {
        this.server = server;
        this.sessions = sessions;
        this.store = store;
        this.events = events;
        this.accounts = accounts;
    }

    @GetMapping("/sessions")
    List<SmppSession.Info> sessions() {
        return sessions.all().stream().map(SmppSession::info).toList();
    }

    @GetMapping("/stats")
    Stats stats() {
        return new Stats(server.port(), store.size(), store.capacity(), sessions.all().size(),
                sessions.bound().size(), events.subscribers(), accounts.all().stream().map(a -> a.systemId()).toList());
    }

    record Stats(int smppPort, int messages, int capacity, int sessions, int boundSessions, int streamSubscribers,
                 List<String> accounts) {
    }
}
