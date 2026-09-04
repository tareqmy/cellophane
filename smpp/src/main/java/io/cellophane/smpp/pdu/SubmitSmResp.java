package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

import java.util.List;
import java.util.Objects;

/** {@code submit_sm_resp}. The message id (and TLVs) are only sent when the status is 0. */
public record SubmitSmResp(int commandStatus, int sequenceNumber, String messageId, List<Tlv> tlvs) implements Pdu {

    public SubmitSmResp {
        Objects.requireNonNull(messageId, "messageId");
        tlvs = List.copyOf(tlvs);
    }

    @Override
    public int commandId() {
        return CommandId.SUBMIT_SM_RESP.id();
    }
}
