package io.cellophane.server.message;

import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.util.Collection;

/**
 * Where a message is in its life: the submit outcome until a receipt is issued, then the receipt's state using
 * the SMPP stat token names so the API reads like the wire.
 */
public enum MessageStatus {
    /** submit_sm answered with ESME_ROK; no receipt yet. */
    ACCEPTED(3),
    /** submit_sm answered with an error status. */
    REJECTED(5),
    DELIVRD(1),
    ACCEPTD(2),
    ENROUTE(2),
    UNKNOWN(2),
    UNDELIV(4),
    EXPIRED(4),
    DELETED(4),
    REJECTD(4);

    private final int rank;

    MessageStatus(int rank) {
        this.rank = rank;
    }

    public static MessageStatus of(DeliveryReceipt.State state) {
        return valueOf(state.stat());
    }

    /**
     * The status of a multi-part message: a rejected part dominates, then a failed receipt, then a part still
     * waiting, and only when every part is delivered is the message delivered.
     */
    public static MessageStatus combine(Collection<MessageStatus> parts) {
        MessageStatus worst = DELIVRD;
        for (MessageStatus s : parts) {
            if (s.rank > worst.rank) {
                worst = s;
            }
        }
        return worst;
    }
}
