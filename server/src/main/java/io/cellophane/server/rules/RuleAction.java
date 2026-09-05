package io.cellophane.server.rules;

/**
 * What the fake operator does with a submit that matched a rule. {@link Accept} and {@link Reject} decide the
 * answer; {@link Throttle}, {@link Latency} and {@link Disconnect} are gates that add an effect and let evaluation
 * continue to the following rules (a throttle over its limit becomes a reject).
 */
public sealed interface RuleAction permits Accept, Reject, Throttle, Latency, Disconnect {

    /** Whether this action ends evaluation. */
    default boolean isTerminal() {
        return this instanceof Accept || this instanceof Reject;
    }
}
