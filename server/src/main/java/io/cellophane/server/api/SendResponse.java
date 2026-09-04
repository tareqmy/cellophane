package io.cellophane.server.api;

/** Result of {@code POST /api/v1/send}: the stored message, the SMPP status the operator answered, and which rule. */
public record SendResponse(MessageSummary message, String smppStatus, String rule) {
}
