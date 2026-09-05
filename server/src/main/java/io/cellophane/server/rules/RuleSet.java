package io.cellophane.server.rules;

import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered rule list with a fallback. Evaluation walks the list: gates (throttle, latency, disconnect) apply
 * their effect and continue; the first accept or reject, a throttle over its limit, or the default ends it.
 */
public record RuleSet(List<Rule> rules, RuleAction defaultAction) {

    public static final String DEFAULT_RULE_NAME = "default";

    /** What the operator does when no rule matches and none is configured: deliver after half a second. */
    public static final RuleAction BUILT_IN_DEFAULT = Accept.receipt(DeliveryReceipt.State.DELIVERED,
            Duration.ofMillis(500));

    public static final RuleSet DEFAULT = new RuleSet(List.of(), BUILT_IN_DEFAULT);

    public RuleSet {
        rules = List.copyOf(rules);
        Objects.requireNonNull(defaultAction, "defaultAction");
        if (!defaultAction.isTerminal()) {
            throw new IllegalArgumentException("the default must accept or reject, not " + defaultAction);
        }
    }

    public Decision decide(RuleContext ctx, Gates gates) {
        Duration latency = Duration.ZERO;
        boolean disconnect = false;
        List<String> path = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.match().matches(ctx)) {
                continue;
            }
            path.add(rule.name());
            switch (rule.action()) {
                case Accept a -> {
                    return new Decision(rule.name(), a, latency, disconnect, path);
                }
                case Reject r -> {
                    return new Decision(rule.name(), r, latency, disconnect, path);
                }
                case Throttle t -> {
                    if (gates.overThrottle(rule, t, ctx)) {
                        return new Decision(rule.name(), new Reject(t.status()), latency, disconnect, path);
                    }
                }
                case Latency l -> latency = latency.plus(l.delay().pick(java.util.random.RandomGenerator.getDefault()));
                case Disconnect d -> disconnect |= gates.disconnectDue(rule, d, ctx);
            }
        }
        path.add(DEFAULT_RULE_NAME);
        return new Decision(DEFAULT_RULE_NAME, defaultAction, latency, disconnect, path);
    }

    /** Evaluates with gates that never trigger. */
    public Decision decide(RuleContext ctx) {
        return decide(ctx, Gates.NONE);
    }
}
