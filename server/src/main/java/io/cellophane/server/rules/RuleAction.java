package io.cellophane.server.rules;

/** What the fake operator does with a submit that matched a rule. */
public sealed interface RuleAction permits Accept, Reject {
}
