package io.cellophane.server.api;

import io.cellophane.server.message.Message;

import java.time.Instant;

/** The list-view shape of a message. */
public record MessageSummary(String id, Instant receivedAt, Instant updatedAt, String account, String from,
                             String to, String text, String encoding, int parts, int partsReceived, String status) {

    public static MessageSummary of(Message m) {
        return new MessageSummary(m.id(), m.receivedAt(), m.updatedAt(), m.account(), m.from().address(),
                m.to().address(), m.text(), m.encoding(), m.parts(), m.partsReceived(), m.status().name());
    }
}
