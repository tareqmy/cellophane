package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

import java.util.List;

/** {@code deliver_sm}: the SMSC delivering a mobile-originated message or a delivery receipt to an ESME. */
public record DeliverSm(int sequenceNumber, String serviceType, Address source, Address destination, int esmClass,
                        int protocolId, int priorityFlag, String scheduleDeliveryTime, String validityPeriod,
                        int registeredDelivery, int replaceIfPresent, int dataCoding, int smDefaultMsgId,
                        byte[] shortMessage, List<Tlv> tlvs) implements Pdu, MessagePdu {

    public DeliverSm {
        PduRecords.requireMessageFields(serviceType, source, destination, scheduleDeliveryTime, validityPeriod,
                shortMessage, tlvs);
        tlvs = List.copyOf(tlvs);
    }

    /** A plain mobile-originated message. */
    public static DeliverSm of(int sequenceNumber, Address source, Address destination, int dataCoding,
                               byte[] shortMessage) {
        return new DeliverSm(sequenceNumber, "", source, destination, 0, 0, 0, "", "", 0, 0, dataCoding, 0,
                shortMessage, List.of());
    }

    /** A delivery receipt: esm_class marks it as such and the receipt text goes in short_message. */
    public static DeliverSm receipt(int sequenceNumber, Address source, Address destination, byte[] receiptText,
                                    List<Tlv> tlvs) {
        return new DeliverSm(sequenceNumber, "", source, destination, ESM_DELIVERY_RECEIPT, 0, 0, "", "", 0, 0, 0, 0,
                receiptText, tlvs);
    }

    @Override
    public int commandId() {
        return CommandId.DELIVER_SM.id();
    }

    @Override
    public int commandStatus() {
        return 0;
    }

    /** The same PDU under a different sequence number, for sending a prepared receipt on a chosen session. */
    public DeliverSm withSequenceNumber(int newSequenceNumber) {
        return new DeliverSm(newSequenceNumber, serviceType, source, destination, esmClass, protocolId, priorityFlag,
                scheduleDeliveryTime, validityPeriod, registeredDelivery, replaceIfPresent, dataCoding,
                smDefaultMsgId, shortMessage, tlvs);
    }

    public DeliverSmResp respond(int commandStatus) {
        return new DeliverSmResp(commandStatus, sequenceNumber);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DeliverSm other && PduRecords.messageEquals(this, other);
    }

    @Override
    public int hashCode() {
        return PduRecords.messageHash(this);
    }

    @Override
    public String toString() {
        return PduRecords.messageString("DeliverSm", this);
    }
}
