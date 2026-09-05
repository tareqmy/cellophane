package io.cellophane.server.message;

/** Things that happen to a message part after it arrives, in the order the fake operator does them. */
public enum EventType {
    /** submit_sm answered with ESME_ROK. */
    ACCEPTED,
    /** submit_sm answered with an error status. */
    REJECTED,
    /** The response was held back by a latency rule. */
    DELAYED,
    /** The connection was dropped after the response, by a disconnect rule. */
    DISCONNECTED,
    /** A delivery receipt has been decided on and will be sent after a delay. */
    DLR_SCHEDULED,
    /** No receipt will be sent, and why. */
    DLR_SKIPPED,
    /** The receipt was written to a receiver session as deliver_sm. */
    DLR_SENT,
    /** No receiver session was bound for the account; the receipt waits for one. */
    DLR_QUEUED,
    /** The ESME answered the receipt with deliver_sm_resp. */
    DLR_ACKED,
    /** Receipt applied without SMPP, for messages sent over HTTP. */
    DLR_SIMULATED
}
