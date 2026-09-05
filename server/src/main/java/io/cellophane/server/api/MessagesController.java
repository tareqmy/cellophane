package io.cellophane.server.api;

import io.cellophane.server.message.Inbox;
import io.cellophane.server.message.MessageQuery;
import io.cellophane.server.message.MessageStatus;
import io.cellophane.server.message.MessageStore;
import io.cellophane.server.message.Since;
import io.cellophane.server.operator.Operator;
import io.cellophane.smpp.CommandStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** The inbox over HTTP: search, fetch, clear, live stream, and the non-SMPP send endpoint. */
@RestController
@RequestMapping("/api/v1")
class MessagesController {

    private final MessageStore store;
    private final Inbox inbox;
    private final MessageEvents events;
    private final Operator operator;
    private final Clock clock;

    MessagesController(MessageStore store, Inbox inbox, MessageEvents events, Operator operator, Clock clock) {
        this.store = store;
        this.inbox = inbox;
        this.events = events;
        this.operator = operator;
        this.clock = clock;
    }

    @GetMapping("/messages")
    MessagesPage list(@RequestParam(required = false) String q,
                      @RequestParam(required = false) String to,
                      @RequestParam(required = false) String from,
                      @RequestParam(required = false) String text,
                      @RequestParam(required = false) String account,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) String since,
                      @RequestParam(defaultValue = "0") int offset,
                      @RequestParam(defaultValue = "" + MessageQuery.DEFAULT_LIMIT) int limit) {
        MessageQuery query;
        try {
            Instant sinceInstant = since == null || since.isBlank() ? null : Since.parse(since, clock);
            query = new MessageQuery(q, to, from, text, account, statuses(status), sinceInstant, offset, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        MessageStore.Page page = store.list(query);
        return new MessagesPage(page.total(), page.messages().stream().map(MessageSummary::of).toList());
    }

    @GetMapping("/messages/{id}")
    MessageDetail get(@PathVariable String id) {
        return store.get(id).map(MessageDetail::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no message " + id));
    }

    @DeleteMapping("/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear() {
        inbox.clear();
    }

    @GetMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return events.subscribe();
    }

    /** Sends through the operator's rules like an SMPP submit; a rejected message is still stored and returned. */
    @PostMapping("/send")
    @ResponseStatus(HttpStatus.CREATED)
    SendResponse send(@RequestBody SendRequest request) {
        if (isBlank(request.from()) || isBlank(request.to()) || request.text() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from, to and text are required");
        }
        Operator.Outcome outcome = operator.sendHttp(request.from().trim(), request.to().trim(), request.text());
        return new SendResponse(MessageSummary.of(outcome.accepted().message()),
                CommandStatus.describe(outcome.commandStatus()), outcome.decision().rule());
    }

    /** {@code status=REJECTED} or {@code status=UNDELIV,EXPIRED}; case-insensitive. */
    private static Set<MessageStatus> statuses(String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        Set<MessageStatus> set = EnumSet.noneOf(MessageStatus.class);
        for (String token : param.split(",")) {
            try {
                set.add(MessageStatus.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown status '" + token.trim() + "'; use one of "
                        + java.util.Arrays.toString(MessageStatus.values()));
            }
        }
        return set;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
