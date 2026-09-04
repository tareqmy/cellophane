package io.cellophane.smpp.pdu;

import java.util.List;
import java.util.Optional;

/** Fields shared by {@code submit_sm} and {@code deliver_sm}, which have identical bodies. */
public interface MessagePdu {

    /** {@code esm_class} bit: user data header indicator. */
    int ESM_UDHI = 0x40;
    /** {@code esm_class} bit: reply path. */
    int ESM_REPLY_PATH = 0x80;
    /** {@code esm_class} message-type bits (bits 2-5). */
    int ESM_MESSAGE_TYPE_MASK = 0x3C;
    /** {@code esm_class} message type: SMSC delivery receipt. */
    int ESM_DELIVERY_RECEIPT = 0x04;

    /** {@code registered_delivery} bits 0-1: request a final delivery receipt. */
    int REGISTERED_DELIVERY_FINAL = 0x01;
    /** {@code registered_delivery} bits 0-1: request a receipt on failure only. */
    int REGISTERED_DELIVERY_FAILURE = 0x02;

    int sequenceNumber();

    String serviceType();

    Address source();

    Address destination();

    int esmClass();

    int protocolId();

    int priorityFlag();

    String scheduleDeliveryTime();

    String validityPeriod();

    int registeredDelivery();

    int replaceIfPresent();

    int dataCoding();

    int smDefaultMsgId();

    byte[] shortMessage();

    List<Tlv> tlvs();

    default boolean hasUdh() {
        return (esmClass() & ESM_UDHI) != 0;
    }

    default boolean isDeliveryReceipt() {
        return (esmClass() & ESM_MESSAGE_TYPE_MASK) == ESM_DELIVERY_RECEIPT;
    }

    default boolean wantsDeliveryReceipt() {
        return (registeredDelivery() & 0x03) != 0;
    }

    default Optional<Tlv> tlv(int tag) {
        for (Tlv tlv : tlvs()) {
            if (tlv.tag() == tag) {
                return Optional.of(tlv);
            }
        }
        return Optional.empty();
    }

    /** The user data: {@code message_payload} when present, otherwise {@code short_message}. Includes any UDH. */
    default byte[] payload() {
        return tlv(Tlv.Tag.MESSAGE_PAYLOAD).map(Tlv::value).orElseGet(this::shortMessage);
    }
}
