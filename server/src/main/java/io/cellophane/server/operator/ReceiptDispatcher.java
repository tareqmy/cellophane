package io.cellophane.server.operator;

import io.cellophane.server.message.EventType;
import io.cellophane.server.message.Inbox;
import io.cellophane.server.message.MessageStatus;
import io.cellophane.server.smpp.SessionRegistry;
import io.cellophane.server.smpp.SmppSession;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.DeliverSmResp;
import io.cellophane.smpp.receipt.DeliveryReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sends receipts to a bound receiver of the right account, holding them while none is bound, the way an
 * operator's store-and-forward queue does. HTTP-sent messages have no ESME, so their receipts are applied directly.
 */
public final class ReceiptDispatcher implements ReceiptSink {

    static final int MAX_QUEUED_PER_ACCOUNT = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ReceiptDispatcher.class);

    private record Pending(String segmentMessageId, DeliverSm receipt, DeliveryReceipt.State state) {
    }

    private final SessionRegistry sessions;
    private final Inbox inbox;
    private final Metrics metrics;
    private final Map<String, Deque<Pending>> queued = new HashMap<>();

    public ReceiptDispatcher(SessionRegistry sessions, Inbox inbox, Metrics metrics) {
        this.sessions = sessions;
        this.inbox = inbox;
        this.metrics = metrics;
    }

    @Override
    public synchronized void deliver(String account, String segmentMessageId, DeliverSm receipt,
                                     DeliveryReceipt.State state) {
        if (Inbox.HTTP_ACCOUNT.equals(account)) {
            inbox.record(segmentMessageId, EventType.DLR_SIMULATED,
                    "stat:" + state.stat() + " (HTTP message, no SMPP receiver to deliver a receipt to)",
                    MessageStatus.of(state));
            return;
        }
        Optional<SmppSession> receiver = sessions.receiverFor(account);
        if (receiver.isPresent()) {
            send(receiver.get(), new Pending(segmentMessageId, receipt, state));
            return;
        }
        Deque<Pending> queue = queued.computeIfAbsent(account, a -> new ArrayDeque<>());
        if (queue.size() >= MAX_QUEUED_PER_ACCOUNT) {
            Pending dropped = queue.pollFirst();
            inbox.record(dropped.segmentMessageId(), EventType.DLR_SKIPPED, "receipt queue full, receipt dropped",
                    null);
        }
        queue.addLast(new Pending(segmentMessageId, receipt, state));
        inbox.record(segmentMessageId, EventType.DLR_QUEUED,
                "stat:" + state.stat() + " waiting: no receiver or transceiver bound for account '" + account + "'",
                null);
    }

    /** Called when a session binds with receive capability: hands it everything queued for its account. */
    public synchronized void flush(SmppSession session) {
        String account = session.account().map(a -> a.systemId()).orElse(null);
        if (account == null || !session.canReceive()) {
            return;
        }
        Deque<Pending> queue = queued.remove(account);
        if (queue == null) {
            return;
        }
        log.info("[{}] delivering {} queued receipt(s) for '{}'", session.id(), queue.size(), account);
        for (Pending p : queue) {
            send(session, p);
        }
    }

    /** Matches a deliver_sm_resp to the receipt it acknowledges. */
    public void onReceiptAck(SmppSession session, DeliverSmResp resp) {
        session.receiptAcked(resp.sequenceNumber()).ifPresent(segmentId -> inbox.record(segmentId,
                EventType.DLR_ACKED, "deliver_sm_resp " + CommandStatus.describe(resp.commandStatus())
                        + " from " + session.id(), null));
    }

    public synchronized int queuedFor(String account) {
        Deque<Pending> q = queued.get(account);
        return q == null ? 0 : q.size();
    }

    private void send(SmppSession session, Pending p) {
        int seq = session.nextSequence();
        session.expectReceiptAck(seq, p.segmentMessageId());
        session.channel().writeAndFlush(p.receipt().withSequenceNumber(seq));
        metrics.receiptSent();
        inbox.record(p.segmentMessageId(), EventType.DLR_SENT,
                "deliver_sm stat:" + p.state().stat() + " to " + session.id() + " seq " + seq,
                MessageStatus.of(p.state()));
    }
}
