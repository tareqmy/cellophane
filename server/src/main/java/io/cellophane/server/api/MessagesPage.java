package io.cellophane.server.api;

import java.util.List;

/** A page of search results; {@code total} counts every match. */
public record MessagesPage(int total, List<MessageSummary> messages) {
}
