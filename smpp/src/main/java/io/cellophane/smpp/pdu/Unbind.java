package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

public record Unbind(int sequenceNumber) implements Pdu {

    @Override
    public int commandId() {
        return CommandId.UNBIND.id();
    }

    @Override
    public int commandStatus() {
        return 0;
    }

    public UnbindResp respond(int commandStatus) {
        return new UnbindResp(commandStatus, sequenceNumber);
    }
}
