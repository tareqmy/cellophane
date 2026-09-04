package io.cellophane.server.rules;

/** The facts a rule can match on for one submit. {@code text} is null for binary payloads. */
public record RuleContext(String account, String from, String to, String text) {
}
