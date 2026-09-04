package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

public record EnquireLink(int sequenceNumber) implements Pdu {

    @Override
    public int commandId() {
        return CommandId.ENQUIRE_LINK.id();
    }

    @Override
    public int commandStatus() {
        return 0;
    }

    public EnquireLinkResp respond() {
        return new EnquireLinkResp(0, sequenceNumber);
    }
}
