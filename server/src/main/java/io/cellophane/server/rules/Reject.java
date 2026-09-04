package io.cellophane.server.rules;

import io.cellophane.smpp.CommandStatus;

/** Answer the submit with an SMPP error status instead of accepting it. */
public record Reject(int commandStatus) implements RuleAction {

    public Reject {
        if (commandStatus == CommandStatus.ESME_ROK.code()) {
            throw new IllegalArgumentException("reject needs a non-zero status");
        }
    }

    public static Reject with(CommandStatus status) {
        return new Reject(status.code());
    }
}
