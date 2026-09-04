package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

import java.util.List;

/** {@code submit_sm}: an ESME submitting a message to the SMSC. */
public record SubmitSm(int sequenceNumber, String serviceType, Address source, Address destination, int esmClass,
                       int protocolId, int priorityFlag, String scheduleDeliveryTime, String validityPeriod,
                       int registeredDelivery, int replaceIfPresent, int dataCoding, int smDefaultMsgId,
                       byte[] shortMessage, List<Tlv> tlvs) implements Pdu, MessagePdu {

    public SubmitSm {
        PduRecords.requireMessageFields(serviceType, source, destination, scheduleDeliveryTime, validityPeriod,
                shortMessage, tlvs);
        tlvs = List.copyOf(tlvs);
    }

    /** A plain submit with the given data coding and encoded text, no registered delivery. */
    public static SubmitSm of(int sequenceNumber, Address source, Address destination, int dataCoding,
                              byte[] shortMessage) {
        return new SubmitSm(sequenceNumber, "", source, destination, 0, 0, 0, "", "", 0, 0, dataCoding, 0,
                shortMessage, List.of());
    }

    @Override
    public int commandId() {
        return CommandId.SUBMIT_SM.id();
    }

    @Override
    public int commandStatus() {
        return 0;
    }

    public SubmitSmResp respond(int commandStatus, String messageId) {
        return new SubmitSmResp(commandStatus, sequenceNumber, messageId, List.of());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SubmitSm other && PduRecords.messageEquals(this, other);
    }

    @Override
    public int hashCode() {
        return PduRecords.messageHash(this);
    }

    @Override
    public String toString() {
        return PduRecords.messageString("SubmitSm", this);
    }
}
