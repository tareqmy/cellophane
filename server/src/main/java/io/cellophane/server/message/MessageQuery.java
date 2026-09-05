package io.cellophane.server.message;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Inbox search. {@code q} matches sender, recipient or text; the named fields narrow further. Text matching is
 * case-insensitive containment, account is exact, {@code statuses} is any-of; nulls (or an empty set) match all.
 */
public record MessageQuery(String q, String to, String from, String text, String account, Set<MessageStatus> statuses,
                           Instant since, int offset, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 1000;

    public MessageQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static MessageQuery all(int limit) {
        return new MessageQuery(null, null, null, null, null, null, null, 0, limit);
    }

    public boolean matches(Message m) {
        return matchesAny(m)
                && contains(m.to().address(), to)
                && contains(m.from().address(), from)
                && contains(m.text(), text)
                && (account == null || account.equals(m.account()))
                && (statuses == null || statuses.isEmpty() || statuses.contains(m.status()))
                && (since == null || !m.receivedAt().isBefore(since));
    }

    private boolean matchesAny(Message m) {
        if (q == null || q.isBlank()) {
            return true;
        }
        return contains(m.to().address(), q) || contains(m.from().address(), q) || contains(m.text(), q);
    }

    private static boolean contains(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return true;
        }
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
