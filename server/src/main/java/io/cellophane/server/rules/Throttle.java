package io.cellophane.server.rules;

import io.cellophane.smpp.CommandStatus;

/**
 * Allow at most {@code tps} matching submits per second per account; answer the rest with {@code status}
 * (normally {@code ESME_RTHROTTLED}). Submits under the limit continue to the following rules.
 */
public record Throttle(int tps, int status) implements RuleAction {

    public Throttle {
        if (tps < 1) {
            throw new IllegalArgumentException("tps must be at least 1");
        }
        if (status == CommandStatus.ESME_ROK.code()) {
            throw new IllegalArgumentException("throttle status must not be ESME_ROK");
        }
    }

    public static Throttle of(int tps) {
        return new Throttle(tps, CommandStatus.ESME_RTHROTTLED.code());
    }
}
