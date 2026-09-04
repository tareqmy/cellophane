package io.cellophane.smpp.pdu;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Shared equality/formatting helpers for records that hold byte arrays. */
final class PduRecords {

    private PduRecords() {
    }

    static boolean messageEquals(MessagePdu a, MessagePdu b) {
        return a.sequenceNumber() == b.sequenceNumber()
                && a.serviceType().equals(b.serviceType())
                && a.source().equals(b.source())
                && a.destination().equals(b.destination())
                && a.esmClass() == b.esmClass()
                && a.protocolId() == b.protocolId()
                && a.priorityFlag() == b.priorityFlag()
                && a.scheduleDeliveryTime().equals(b.scheduleDeliveryTime())
                && a.validityPeriod().equals(b.validityPeriod())
                && a.registeredDelivery() == b.registeredDelivery()
                && a.replaceIfPresent() == b.replaceIfPresent()
                && a.dataCoding() == b.dataCoding()
                && a.smDefaultMsgId() == b.smDefaultMsgId()
                && Arrays.equals(a.shortMessage(), b.shortMessage())
                && a.tlvs().equals(b.tlvs());
    }

    static int messageHash(MessagePdu m) {
        return Objects.hash(m.sequenceNumber(), m.serviceType(), m.source(), m.destination(), m.esmClass(),
                m.protocolId(), m.priorityFlag(), m.scheduleDeliveryTime(), m.validityPeriod(),
                m.registeredDelivery(), m.replaceIfPresent(), m.dataCoding(), m.smDefaultMsgId(),
                Arrays.hashCode(m.shortMessage()), m.tlvs());
    }

    static String messageString(String name, MessagePdu m) {
        return name + "[seq=" + m.sequenceNumber()
                + ", serviceType=" + m.serviceType()
                + ", source=" + m.source()
                + ", destination=" + m.destination()
                + ", esmClass=0x" + Integer.toHexString(m.esmClass())
                + ", protocolId=" + m.protocolId()
                + ", priorityFlag=" + m.priorityFlag()
                + ", scheduleDeliveryTime=" + m.scheduleDeliveryTime()
                + ", validityPeriod=" + m.validityPeriod()
                + ", registeredDelivery=" + m.registeredDelivery()
                + ", replaceIfPresent=" + m.replaceIfPresent()
                + ", dataCoding=0x" + Integer.toHexString(m.dataCoding())
                + ", smDefaultMsgId=" + m.smDefaultMsgId()
                + ", shortMessage=" + HexFormat.of().formatHex(m.shortMessage())
                + ", tlvs=" + m.tlvs() + "]";
    }

    static void requireMessageFields(String serviceType, Address source, Address destination,
                                     String scheduleDeliveryTime, String validityPeriod,
                                     byte[] shortMessage, List<Tlv> tlvs) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(scheduleDeliveryTime, "scheduleDeliveryTime");
        Objects.requireNonNull(validityPeriod, "validityPeriod");
        Objects.requireNonNull(shortMessage, "shortMessage");
        Objects.requireNonNull(tlvs, "tlvs");
        if (shortMessage.length > 255) {
            throw new IllegalArgumentException("short_message longer than 255 bytes; use message_payload");
        }
    }
}
