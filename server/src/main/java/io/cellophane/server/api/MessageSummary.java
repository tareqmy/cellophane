package io.cellophane.server.api;

import io.cellophane.server.message.Message;
import io.cellophane.smpp.text.Udh;

import java.time.Instant;

/** The list-view shape of a message. */
public record MessageSummary(String id, Instant receivedAt, String account, String from, String to, String text,
                             String encoding, int parts, Integer part, String status) {

    public static MessageSummary of(Message m) {
        Udh.Concat concat = m.concat().orElse(null);
        return new MessageSummary(m.id(), m.receivedAt(), m.account(), m.from().address(), m.to().address(),
                m.text(), m.encoding(), concat == null ? 1 : concat.total(),
                concat == null ? null : concat.sequence(), m.status().name());
    }
}
