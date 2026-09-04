package io.cellophane.server.message;

/** Where a message is in its life. Only acceptance exists until delivery receipts arrive in a later milestone. */
public enum MessageStatus {
    /** submit_sm answered with ESME_ROK. */
    ACCEPTED
}
