package io.cellophane.server.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory inbox: the most recent messages in arrival order, indexed by message id and by the id of any
 * part. Updating a message keeps its position, so a concatenated message stays where its first part put it.
 */
public final class MessageStore {

    private final int capacity;
    private final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
    private final Map<String, String> messageBySegment = new HashMap<>();

    public MessageStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    public synchronized void add(Message message) {
        if (messages.containsKey(message.id())) {
            throw new IllegalArgumentException("duplicate message id " + message.id());
        }
        if (messages.size() >= capacity) {
            Message evicted = messages.pollFirstEntry().getValue();
            evicted.segments().forEach(s -> messageBySegment.remove(s.messageId()));
        }
        messages.put(message.id(), message);
        message.segments().forEach(s -> messageBySegment.put(s.messageId(), message.id()));
    }

    /** Replaces a stored message in place. Returns false if it is no longer stored (evicted or cleared). */
    public synchronized boolean update(Message message) {
        if (!messages.containsKey(message.id())) {
            return false;
        }
        messages.put(message.id(), message);
        message.segments().forEach(s -> messageBySegment.put(s.messageId(), message.id()));
        return true;
    }

    /** Newest first. {@code total} counts every match, not just the returned page. */
    public synchronized Page list(MessageQuery query) {
        int total = 0;
        List<Message> page = new ArrayList<>();
        for (Message m : messages.reversed().values()) {
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
        return Optional.ofNullable(messages.get(id));
    }

    /** The message one of whose parts has the given SMPP message_id. */
    public synchronized Optional<Message> findBySegment(String segmentMessageId) {
        return Optional.ofNullable(messageBySegment.get(segmentMessageId)).map(messages::get);
    }

    /** Removes everything and returns how many messages were dropped. */
    public synchronized int clear() {
        int n = messages.size();
        messages.clear();
        messageBySegment.clear();
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
