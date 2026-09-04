package io.cellophane.server.operator;

import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.receipt.DeliveryReceipt;

/** Where a due delivery receipt goes: to a bound receiver, a queue, or a simulated ack. */
public interface ReceiptSink {

    /**
     * @param account          the ESME account the receipt belongs to
     * @param segmentMessageId the part the receipt is for
     * @param receipt          the deliver_sm to send; its sequence number is assigned by the session that sends it
     */
    void deliver(String account, String segmentMessageId, DeliverSm receipt, DeliveryReceipt.State state);
}
