package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

public record UnbindResp(int commandStatus, int sequenceNumber) implements Pdu {

    @Override
    public int commandId() {
        return CommandId.UNBIND_RESP.id();
    }
}
