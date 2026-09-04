package io.cellophane.server.rules;

import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** An ordered rule list evaluated first-match, with a fallback action. */
public record RuleSet(List<Rule> rules, RuleAction defaultAction) {

    public static final String DEFAULT_RULE_NAME = "default";

    /** What the operator does when no rule matches and none is configured: deliver after half a second. */
    public static final RuleAction BUILT_IN_DEFAULT = Accept.receipt(DeliveryReceipt.State.DELIVERED,
            Duration.ofMillis(500));

    public static final RuleSet DEFAULT = new RuleSet(List.of(), BUILT_IN_DEFAULT);

    public RuleSet {
        rules = List.copyOf(rules);
        Objects.requireNonNull(defaultAction, "defaultAction");
    }

    public Decision decide(RuleContext ctx) {
        for (Rule rule : rules) {
            if (rule.match().matches(ctx)) {
                return new Decision(rule.name(), rule.action());
            }
        }
        return new Decision(DEFAULT_RULE_NAME, defaultAction);
    }
}
