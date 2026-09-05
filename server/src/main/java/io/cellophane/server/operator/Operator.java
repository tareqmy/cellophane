package io.cellophane.server.operator;

import io.cellophane.server.message.Event;
import io.cellophane.server.message.EventType;
import io.cellophane.server.message.Inbox;
import io.cellophane.server.message.MessageStatus;
import io.cellophane.server.rules.Accept;
import io.cellophane.server.rules.Decision;
import io.cellophane.server.rules.Disconnect;
import io.cellophane.server.rules.Gates;
import io.cellophane.server.rules.Reject;
import io.cellophane.server.rules.Rule;
import io.cellophane.server.rules.RuleContext;
import io.cellophane.server.rules.RuleEngine;
import io.cellophane.server.rules.Throttle;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.MessagePdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * The fake operator's decisions: for every submit, consult the rules, answer the ESME, store the message with
 * its verdict, and arrange the delivery receipt the rules call for. Also keeps the state the gate rules need:
 * per-account throttle windows and per-session disconnect counters.
 */
public final class Operator implements Gates {

    /**
     * What to do for the ESME: the status and message id to answer, how long to wait before answering, and
     * whether to drop the connection afterwards. {@code messageId} is empty when the submit was rejected.
     */
    public record Outcome(int commandStatus, String messageId, Duration latency, boolean disconnect,
                          Inbox.Accepted accepted, Decision decision) {
    }

    private record ThrottleWindow(long second, AtomicInteger count) {
    }

    private final RuleEngine rules;
    private final Inbox inbox;
    private final ReceiptSink receipts;
    private final DelayedExecutor timer;
    private final Clock clock;
    private final RandomGenerator random;
    private final Metrics metrics;
    private final Map<String, ThrottleWindow> throttles = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> disconnectCounts = new ConcurrentHashMap<>();

    public Operator(RuleEngine rules, Inbox inbox, ReceiptSink receipts, DelayedExecutor timer, Clock clock,
                    RandomGenerator random, Metrics metrics) {
        this.rules = rules;
        this.inbox = inbox;
        this.receipts = receipts;
        this.timer = timer;
        this.clock = clock;
        this.random = random;
        this.metrics = metrics;
    }

    public Outcome onSubmit(String account, String sessionId, SubmitSm submit, byte[] rawPdu) {
        Inbox.Decoded decoded = Inbox.decode(submit);
        RuleContext ctx = new RuleContext(account, sessionId, submit.source().address(),
                submit.destination().address(), decoded.text());
        Decision decision = rules.decide(ctx, this);
        Instant now = clock.instant();
        String by = " (rule: " + decision.rule() + ")";
        Outcome outcome = switch (decision.action()) {
            case Reject reject -> {
                String status = CommandStatus.describe(reject.commandStatus());
                Inbox.Accepted stored = inbox.receive(account, sessionId, submit, rawPdu, decoded,
                        MessageStatus.REJECTED, new Event(now, EventType.REJECTED, "submit_sm_resp " + status + by));
                metrics.submitted(false);
                yield new Outcome(reject.commandStatus(), "", decision.latency(), decision.disconnect(), stored,
                        decision);
            }
            case Accept accept -> {
                Inbox.Accepted stored = inbox.receive(account, sessionId, submit, rawPdu, decoded,
                        MessageStatus.ACCEPTED, new Event(now, EventType.ACCEPTED, "submit_sm_resp ESME_ROK" + by));
                metrics.submitted(true);
                arrangeReceipt(account, stored, submit, accept.pick(random), now, decision.latency());
                yield new Outcome(CommandStatus.ESME_ROK.code(), stored.segment().messageId(), decision.latency(),
                        decision.disconnect(), stored, decision);
            }
            default -> throw new IllegalStateException("non-terminal decision " + decision);
        };
        String segmentId = outcome.accepted().segment().messageId();
        if (!decision.latency().isZero()) {
            inbox.record(segmentId, EventType.DELAYED, "submit_sm_resp held for " + human(decision.latency())
                    + gatesIn(decision), null);
        }
        if (decision.disconnect()) {
            inbox.record(segmentId, EventType.DISCONNECTED, "connection dropped after the response"
                    + gatesIn(decision), null);
        }
        return outcome;
    }

    private static String gatesIn(Decision decision) {
        List<String> gates = decision.path().subList(0, decision.path().size() - 1);
        return gates.isEmpty() ? "" : " (rules: " + String.join(", ", gates) + ")";
    }

    /** Sends a message on behalf of an HTTP client, through the same rules as an SMPP submit. */
    public Outcome sendHttp(String from, String to, String text) {
        SubmitSm submit = Inbox.httpSubmit(from, to, text);
        return onSubmit(Inbox.HTTP_ACCOUNT, Inbox.HTTP_ACCOUNT, submit, PduCodec.encode(submit));
    }

    /** Forgets per-session gate state once the ESME has gone. */
    public void sessionClosed(String sessionId) {
        disconnectCounts.keySet().removeIf(k -> k.startsWith(sessionId + "/"));
    }

    // ----------------------------------------------------------------- gates

    @Override
    public boolean overThrottle(Rule rule, Throttle throttle, RuleContext ctx) {
        long second = clock.instant().getEpochSecond();
        String key = rule.name() + "/" + ctx.account();
        ThrottleWindow window = throttles.compute(key, (k, w) -> w == null || w.second() != second
                ? new ThrottleWindow(second, new AtomicInteger()) : w);
        return window.count().incrementAndGet() > throttle.tps();
    }

    @Override
    public boolean disconnectDue(Rule rule, Disconnect disconnect, RuleContext ctx) {
        String key = ctx.sessionId() + "/" + rule.name();
        return disconnectCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() == disconnect.after();
    }

    // -------------------------------------------------------------- receipts

    /** Receipts are timed from the response, so a latency rule never lets a receipt overtake the submit_sm_resp. */
    private void arrangeReceipt(String account, Inbox.Accepted stored, SubmitSm submit, Accept.Outcome outcome,
                                Instant submittedAt, Duration responseLatency) {
        String segmentId = stored.segment().messageId();
        DeliveryReceipt.State state = outcome.state();
        if (state == null) {
            inbox.record(segmentId, EventType.DLR_SKIPPED, "rule sends no receipt", null);
            return;
        }
        int requested = submit.registeredDelivery() & 0x03;
        if (requested == 0) {
            inbox.record(segmentId, EventType.DLR_SKIPPED,
                    "registered_delivery did not ask for a receipt; would have been " + state.stat(), null);
            return;
        }
        if (requested == MessagePdu.REGISTERED_DELIVERY_FAILURE && state == DeliveryReceipt.State.DELIVERED) {
            inbox.record(segmentId, EventType.DLR_SKIPPED,
                    "registered_delivery asked for failure receipts only; delivery succeeded", null);
            return;
        }
        Duration delay = outcome.delay().pick(random);
        inbox.record(segmentId, EventType.DLR_SCHEDULED, state.stat() + " in " + human(delay)
                + (responseLatency.isZero() ? "" : " after the response"), null);
        String originalText = stored.segment().text();
        timer.schedule(responseLatency.plus(delay), () -> {
            DeliverSm dlr = buildReceipt(segmentId, submit, submittedAt, state, originalText);
            receipts.deliver(account, segmentId, dlr, state);
        });
    }

    private DeliverSm buildReceipt(String segmentId, SubmitSm submit, Instant submittedAt,
                                   DeliveryReceipt.State state, String originalText) {
        DeliveryReceipt receipt = DeliveryReceipt.of(segmentId, LocalDateTime.ofInstant(submittedAt, ZoneOffset.UTC),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), state,
                originalText == null ? "" : originalText);
        return DeliverSm.receipt(0, submit.destination(), submit.source(),
                receipt.format().getBytes(StandardCharsets.ISO_8859_1),
                List.of(Tlv.ofCString(Tlv.Tag.RECEIPTED_MESSAGE_ID, segmentId),
                        Tlv.ofByte(Tlv.Tag.MESSAGE_STATE, state.code())));
    }

    static String human(Duration d) {
        long ms = d.toMillis();
        if (ms == 0) {
            return "0ms";
        }
        if (ms % 1000 == 0) {
            return ms / 1000 + "s";
        }
        if (ms >= 1000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        return ms + "ms";
    }
}
