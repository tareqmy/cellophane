package io.cellophane.server.rules;

import java.time.Duration;
import java.util.List;

/**
 * The outcome of evaluating the rules for one submit.
 *
 * @param rule       the rule that decided the answer (a throttle over its limit, an accept/reject, or the default)
 * @param action     the terminal action: always an {@link Accept} or a {@link Reject}
 * @param latency    total delay before the response, from any matching latency rules
 * @param disconnect whether to drop the connection after responding
 * @param path       every rule that matched, in order, including gates
 */
public record Decision(String rule, RuleAction action, Duration latency, boolean disconnect, List<String> path) {

    public Decision {
        if (!action.isTerminal()) {
            throw new IllegalArgumentException("decision needs a terminal action, got " + action);
        }
        path = List.copyOf(path);
    }
}
