package io.cellophane.server.rules;

/**
 * The facts a rule can match on for one submit. {@code text} is null for binary payloads; {@code sessionId}
 * identifies the connection for per-session gates.
 */
public record RuleContext(String account, String sessionId, String from, String to, String text) {
}
