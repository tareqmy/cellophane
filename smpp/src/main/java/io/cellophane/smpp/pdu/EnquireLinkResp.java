package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

public record EnquireLinkResp(int commandStatus, int sequenceNumber) implements Pdu {

    @Override
    public int commandId() {
        return CommandId.ENQUIRE_LINK_RESP.id();
    }
}
