package io.cellophane.server.message;

import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.text.Udh;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A message the fake operator accepted, as stored in the inbox.
 *
 * @param text     decoded user data without any UDH; null when the data coding is binary or unknown
 * @param encoding human-readable alphabet name
 * @param udh      the parsed user data header, or null when none was present or it was malformed
 */
public record Message(String id, Instant receivedAt, String account, String sessionId, SubmitSm pdu, byte[] rawPdu,
                      String text, String encoding, Udh udh, MessageStatus status) {

    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(pdu, "pdu");
        Objects.requireNonNull(rawPdu, "rawPdu");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(status, "status");
    }

    public Address from() {
        return pdu.source();
    }

    public Address to() {
        return pdu.destination();
    }

    public Optional<Udh.Concat> concat() {
        return udh == null ? Optional.empty() : udh.concat();
    }
}
