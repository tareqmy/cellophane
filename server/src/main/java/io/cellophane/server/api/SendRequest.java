package io.cellophane.server.api;

/** Body of {@code POST /api/v1/send}. */
public record SendRequest(String from, String to, String text) {
}
