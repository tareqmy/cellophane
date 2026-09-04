package io.cellophane.server.message;

import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.text.Udh;

import java.time.Instant;
import java.util.Objects;

/**
 * One submit_sm as it arrived on the wire. A single-part message has exactly one; a concatenated message has one
 * per part.
 *
 * @param messageId the SMPP message_id returned to the ESME for this submit, which delivery receipts refer to
 * @param sequence  1-based part number (always 1 for a single-part message)
 * @param text      this part's decoded text without any UDH, or null for binary data
 * @param udh       parsed user data header, or null when absent or unreadable
 */
public record Segment(String messageId, int sequence, Instant receivedAt, String sessionId, SubmitSm pdu,
                      byte[] rawPdu, Udh udh, String text) {

    public Segment {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(pdu, "pdu");
        Objects.requireNonNull(rawPdu, "rawPdu");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
    }
}
