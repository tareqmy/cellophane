package io.cellophane.server.message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded in-memory inbox: a ring of the most recent messages with an id index. */
public final class MessageStore {

    private final int capacity;
    private final ArrayDeque<Message> messages;
    private final Map<String, Message> byId = new HashMap<>();

    public MessageStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.messages = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    public synchronized void add(Message message) {
        if (messages.size() >= capacity) {
            Message evicted = messages.pollFirst();
            byId.remove(evicted.id());
        }
        messages.addLast(message);
        byId.put(message.id(), message);
    }

    /** Newest first. {@code total} counts every match, not just the returned page. */
    public synchronized Page list(MessageQuery query) {
        int total = 0;
        List<Message> page = new ArrayList<>();
        Iterator<Message> newestFirst = messages.descendingIterator();
        while (newestFirst.hasNext()) {
            Message m = newestFirst.next();
            if (!query.matches(m)) {
                continue;
            }
            if (total >= query.offset() && page.size() < query.limit()) {
                page.add(m);
            }
            total++;
        }
        return new Page(total, page);
    }

    public synchronized Optional<Message> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Removes everything and returns how many messages were dropped. */
    public synchronized int clear() {
        int n = messages.size();
        messages.clear();
        byId.clear();
        return n;
    }

    public synchronized int size() {
        return messages.size();
    }

    public int capacity() {
        return capacity;
    }

    public record Page(int total, List<Message> messages) {
    }
}
