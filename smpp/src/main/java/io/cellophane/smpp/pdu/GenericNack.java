package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

/** Sent in reply to a PDU that could not be decoded or whose command id is unknown. */
public record GenericNack(int commandStatus, int sequenceNumber) implements Pdu {

    @Override
    public int commandId() {
        return CommandId.GENERIC_NACK.id();
    }
}
