package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

/** {@code deliver_sm_resp}. Its message_id is always empty in SMPP 3.4. */
public record DeliverSmResp(int commandStatus, int sequenceNumber) implements Pdu {

    @Override
    public int commandId() {
        return CommandId.DELIVER_SM_RESP.id();
    }
}
