package io.cellophane.server.message;

import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.text.Udh;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One submit_sm as it arrived on the wire. A single-part message has exactly one; a concatenated message has one
 * per part. Each carries its own status and timeline because receipts are issued per part.
 *
 * @param messageId the SMPP message_id returned to the ESME for this submit, which delivery receipts refer to
 * @param sequence  1-based part number (always 1 for a single-part message)
 * @param text      this part's decoded text without any UDH, or null for binary data
 * @param udh       parsed user data header, or null when absent or unreadable
 */
public record Segment(String messageId, int sequence, Instant receivedAt, String sessionId, SubmitSm pdu,
                      byte[] rawPdu, Udh udh, String text, MessageStatus status, List<Event> events) {

    public Segment {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(pdu, "pdu");
        Objects.requireNonNull(rawPdu, "rawPdu");
        Objects.requireNonNull(status, "status");
        events = List.copyOf(events);
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
    }

    Segment with(Event event, MessageStatus newStatus) {
        List<Event> all = new ArrayList<>(events);
        all.add(event);
        return new Segment(messageId, sequence, receivedAt, sessionId, pdu, rawPdu, udh, text,
                newStatus == null ? status : newStatus, all);
    }
}
