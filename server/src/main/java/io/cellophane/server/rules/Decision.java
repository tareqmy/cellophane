package io.cellophane.server.rules;

/** The rule that won and the action to carry out. */
public record Decision(String rule, RuleAction action) {
}
