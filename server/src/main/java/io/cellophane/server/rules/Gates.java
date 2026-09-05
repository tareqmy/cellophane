package io.cellophane.server.rules;

/**
 * The stateful side of gate actions, supplied by whoever evaluates rules: whether this submit exceeds a throttle
 * and whether it is the one a disconnect rule has been waiting for.
 */
public interface Gates {

    /** Counts this submit against the rule's per-account limit; true when it is over. */
    boolean overThrottle(Rule rule, Throttle throttle, RuleContext ctx);

    /** Counts this submit for the rule on the session; true when the configured number has been reached. */
    boolean disconnectDue(Rule rule, Disconnect disconnect, RuleContext ctx);

    /** Gates that never trigger; useful when only the terminal decision matters. */
    Gates NONE = new Gates() {
        @Override
        public boolean overThrottle(Rule rule, Throttle throttle, RuleContext ctx) {
            return false;
        }

        @Override
        public boolean disconnectDue(Rule rule, Disconnect disconnect, RuleContext ctx) {
            return false;
        }
    };
}
