package io.cellophane.server.message;

import java.util.List;

/** Notified of inbox changes; the SSE stream is the main implementation. */
public interface MessageListener {

    /** A new message arrived. */
    void onMessage(Message message);

    /** An existing message changed, e.g. another part of a concatenated message arrived. */
    void onUpdated(Message message);

    void onCleared();

    /** One listener that forwards to each of the given ones, in order. */
    static MessageListener all(MessageListener... listeners) {
        List<MessageListener> targets = List.of(listeners);
        return new MessageListener() {
            @Override
            public void onMessage(Message message) {
                targets.forEach(l -> l.onMessage(message));
            }

            @Override
            public void onUpdated(Message message) {
                targets.forEach(l -> l.onUpdated(message));
            }

            @Override
            public void onCleared() {
                targets.forEach(MessageListener::onCleared);
            }
        };
    }
}
