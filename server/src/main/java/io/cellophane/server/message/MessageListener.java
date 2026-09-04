package io.cellophane.server.message;

/** Notified of inbox changes; the SSE stream is the main implementation. */
public interface MessageListener {

    void onMessage(Message message);

    void onCleared();
}
