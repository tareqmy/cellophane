package io.cellophane.server.message;

import java.time.Instant;
import java.util.Objects;

/** One entry in a message part's timeline. */
public record Event(Instant at, EventType type, String detail) {

    public Event {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(type, "type");
        detail = detail == null ? "" : detail;
    }
}
