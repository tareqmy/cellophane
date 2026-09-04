package io.cellophane.server.message;

/** Notified of inbox changes; the SSE stream is the main implementation. */
public interface MessageListener {

    /** A new message arrived. */
    void onMessage(Message message);

    /** An existing message changed, e.g. another part of a concatenated message arrived. */
    void onUpdated(Message message);

    void onCleared();
}
