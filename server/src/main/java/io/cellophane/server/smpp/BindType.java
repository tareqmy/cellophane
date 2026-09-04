package io.cellophane.server.smpp;

import io.cellophane.smpp.CommandId;

/** The direction an ESME bound in. */
public enum BindType {
    TRANSMITTER,
    RECEIVER,
    TRANSCEIVER;

    public static BindType of(CommandId bindCommand) {
        return switch (bindCommand) {
            case BIND_TRANSMITTER -> TRANSMITTER;
            case BIND_RECEIVER -> RECEIVER;
            case BIND_TRANSCEIVER -> TRANSCEIVER;
            default -> throw new IllegalArgumentException("not a bind: " + bindCommand);
        };
    }

    /** Whether the ESME may send submit_sm on this bind. */
    public boolean canTransmit() {
        return this != RECEIVER;
    }

    /** Whether the SMSC may send deliver_sm on this bind. */
    public boolean canReceive() {
        return this != TRANSMITTER;
    }
}
