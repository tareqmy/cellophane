package io.cellophane.server.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.cellophane.server.message.EventType;
import io.cellophane.server.message.Inbox;
import io.cellophane.server.message.Message;
import io.cellophane.server.message.MessageListener;
import io.cellophane.server.message.MessageStatus;
import io.cellophane.server.message.MessageStore;
import io.cellophane.server.message.Segment;
import io.cellophane.server.rules.RuleEngine;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.MessagePdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.receipt.DeliveryReceipt;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.SmsText;
import io.cellophane.smpp.text.Udh;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

class OperatorTest {

    private static final Instant T0 = Instant.parse("2026-09-04T12:00:00Z");

    /** Runs scheduled tasks only when told to. */
    static final class ManualTimer implements DelayedExecutor {
        record Task(Duration delay, Runnable run) {
        }

        final List<Task> tasks = new ArrayList<>();

        @Override
        public void schedule(Duration delay, Runnable task) {
            tasks.add(new Task(delay, task));
        }

        void runAll() {
            List<Task> due = new ArrayList<>(tasks);
            tasks.clear();
            due.forEach(t -> t.run().run());
        }
    }

    record Delivered(String account, String segmentId, DeliverSm receipt, DeliveryReceipt.State state) {
    }

    private final List<Delivered> delivered = new ArrayList<>();
    private final ManualTimer timer = new ManualTimer();
    private final AtomicReference<Instant> now = new AtomicReference<>(T0.plusSeconds(2));
    private final Clock movable = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final Metrics metrics = new Metrics(movable);
    private final MessageStore store = new MessageStore(100);
    private final Inbox inbox = new Inbox(store, new MessageListener() {
        @Override
        public void onMessage(Message message) {
        }

        @Override
        public void onUpdated(Message message) {
        }

        @Override
        public void onCleared() {
        }
    }, Clock.fixed(T0, ZoneOffset.UTC));

    private Operator operator(String rulesYaml) {
        return new Operator(RuleEngine.fromYaml(rulesYaml), inbox,
                (account, segmentId, receipt, state) -> delivered.add(new Delivered(account, segmentId, receipt, state)),
                timer, movable, RandomGenerator.of("L64X128MixRandom"), metrics);
    }

    private static SubmitSm submit(String to, String text, int registeredDelivery) {
        return new SubmitSm(1, "", Address.alphanumeric("MyApp"), Address.international(to), 0, 0, 0, "", "",
                registeredDelivery, 0, 0, 0, Gsm7.encode(text), List.of());
    }

    private Operator.Outcome send(Operator op, SubmitSm sm) {
        return op.onSubmit("app", "s1", sm, PduCodec.encode(sm));
    }

    @Test
    void acceptsSchedulesAndBuildsAStandardReceipt() {
        Operator op = operator("rules:\n  - match: { to: '^88017' }\n    accept: { dlr: DELIVRD, after: 2s }");

        Operator.Outcome out = send(op, submit("8801711111111", "Your OTP is 482913", 1));

        assertThat(out.commandStatus()).isZero();
        assertThat(out.messageId()).isEqualTo(out.accepted().segment().messageId());
        assertThat(out.decision().rule()).isEqualTo("rule 1");
        Segment seg = store.get(out.accepted().message().id()).orElseThrow().segments().getFirst();
        assertThat(seg.status()).isEqualTo(MessageStatus.ACCEPTED);
        assertThat(seg.events()).extracting(e -> e.type()).containsExactly(EventType.ACCEPTED, EventType.DLR_SCHEDULED);
        assertThat(seg.events().get(0).detail()).isEqualTo("submit_sm_resp ESME_ROK (rule: rule 1)");
        assertThat(seg.events().get(1).detail()).isEqualTo("DELIVRD in 2s");
        assertThat(timer.tasks).hasSize(1);
        assertThat(timer.tasks.getFirst().delay()).isEqualTo(Duration.ofSeconds(2));

        timer.runAll();

        assertThat(delivered).hasSize(1);
        Delivered d = delivered.getFirst();
        assertThat(d.account()).isEqualTo("app");
        assertThat(d.segmentId()).isEqualTo(out.messageId());
        assertThat(d.state()).isEqualTo(DeliveryReceipt.State.DELIVERED);
        DeliverSm dlr = d.receipt();
        assertThat(dlr.isDeliveryReceipt()).isTrue();
        assertThat(dlr.source()).isEqualTo(Address.international("8801711111111"));
        assertThat(dlr.destination()).isEqualTo(Address.alphanumeric("MyApp"));
        assertThat(dlr.tlv(Tlv.Tag.RECEIPTED_MESSAGE_ID).orElseThrow().asCString()).isEqualTo(out.messageId());
        assertThat(dlr.tlv(Tlv.Tag.MESSAGE_STATE).orElseThrow().asByte()).isEqualTo(2);
        DeliveryReceipt receipt = DeliveryReceipt.parse(SmsText.decode(0, dlr.shortMessage()).orElseThrow())
                .orElseThrow();
        assertThat(receipt.id()).isEqualTo(out.messageId());
        assertThat(receipt.state()).isEqualTo(DeliveryReceipt.State.DELIVERED);
        assertThat(receipt.submitDate()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 4, 12, 0));
        assertThat(receipt.text()).isEqualTo("Your OTP is 482913");
    }

    @Test
    void rejectsStoreTheMessageAsRejected() {
        Operator op = operator("rules:\n  - match: { text: '(?i)spam' }\n    reject: ESME_RINVDSTADR");

        Operator.Outcome out = send(op, submit("1", "SPAM", 1));

        assertThat(out.commandStatus()).isEqualTo(CommandStatus.ESME_RINVDSTADR.code());
        assertThat(out.messageId()).isEmpty();
        Message m = store.get(out.accepted().message().id()).orElseThrow();
        assertThat(m.status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(m.segments().getFirst().events().getFirst().detail())
                .isEqualTo("submit_sm_resp ESME_RINVDSTADR (rule: rule 1)");
        assertThat(timer.tasks).isEmpty();
    }

    @Test
    void honoursRegisteredDelivery() {
        Operator op = operator("rules:\n  - accept: { dlr: DELIVRD }");

        Operator.Outcome none = send(op, submit("1", "a", 0));
        Operator.Outcome failureOnly = send(op, submit("1", "b", MessagePdu.REGISTERED_DELIVERY_FAILURE));
        Operator.Outcome wanted = send(op, submit("1", "c", 1));

        assertThat(lastEvent(none).type()).isEqualTo(EventType.DLR_SKIPPED);
        assertThat(lastEvent(none).detail()).contains("did not ask");
        assertThat(lastEvent(failureOnly).type()).isEqualTo(EventType.DLR_SKIPPED);
        assertThat(lastEvent(failureOnly).detail()).contains("failure receipts only");
        assertThat(lastEvent(wanted).type()).isEqualTo(EventType.DLR_SCHEDULED);
        assertThat(timer.tasks).hasSize(1);

        Operator failing = operator("rules:\n  - accept: { dlr: UNDELIV }");
        Operator.Outcome failureWanted = send(failing, submit("1", "d", MessagePdu.REGISTERED_DELIVERY_FAILURE));
        assertThat(lastEvent(failureWanted).type()).isEqualTo(EventType.DLR_SCHEDULED);
    }

    @Test
    void silentRuleSkipsTheReceipt() {
        Operator op = operator("rules:\n  - accept:");

        Operator.Outcome out = send(op, submit("1", "x", 1));

        assertThat(lastEvent(out).type()).isEqualTo(EventType.DLR_SKIPPED);
        assertThat(lastEvent(out).detail()).isEqualTo("rule sends no receipt");
        assertThat(timer.tasks).isEmpty();
    }

    @Test
    void eachPartOfAConcatenatedMessageGetsItsOwnReceipt() {
        Operator op = operator("rules:\n  - accept: { dlr: DELIVRD }");
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            SubmitSm part = new SubmitSm(i, "", Address.alphanumeric("A"), Address.international("1"),
                    MessagePdu.ESM_UDHI, 0, 0, "", "", 1, 0, 0, 0,
                    Udh.concat8(9, 2, i).prepend(Gsm7.encode("part " + i)), List.of());
            ids.add(send(op, part).messageId());
        }
        timer.runAll();

        assertThat(store.size()).isEqualTo(1);
        assertThat(delivered).extracting(Delivered::segmentId).containsExactlyElementsOf(ids);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void httpSendGoesThroughTheRules() {
        Operator op = operator("rules:\n  - match: { from: Spammer }\n    reject: ESME_RINVSRCADR");

        Operator.Outcome rejected = op.sendHttp("Spammer", "1", "buy now");
        Operator.Outcome accepted = op.sendHttp("MyApp", "+8801711111111", "hi");

        assertThat(rejected.commandStatus()).isEqualTo(CommandStatus.ESME_RINVSRCADR.code());
        assertThat(rejected.accepted().message().account()).isEqualTo(Inbox.HTTP_ACCOUNT);
        assertThat(accepted.commandStatus()).isZero();
        assertThat(lastEvent(accepted).type()).as("HTTP sends ask for a receipt").isEqualTo(EventType.DLR_SCHEDULED);
    }

    @Test
    void throttleCountsPerAccountPerSecondThenRejects() {
        Operator op = operator("rules:\n  - match: { to: '^88015' }\n    throttle: { tps: 2 }\n"
                + "  - accept: { dlr: none }");

        assertThat(send(op, submit("8801511111111", "1", 0)).commandStatus()).isZero();
        assertThat(send(op, submit("8801511111111", "2", 0)).commandStatus()).isZero();
        Operator.Outcome third = send(op, submit("8801511111111", "3", 0));
        assertThat(third.commandStatus()).isEqualTo(CommandStatus.ESME_RTHROTTLED.code());
        assertThat(third.decision().rule()).isEqualTo("rule 1");
        assertThat(store.get(third.accepted().message().id()).orElseThrow().status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(op.onSubmit("other", "s9", submit("8801511111111", "4", 0), new byte[0]).commandStatus())
                .as("another account has its own window").isZero();
        assertThat(send(op, submit("15551234567", "5", 0)).commandStatus()).as("unmatched recipient").isZero();

        now.set(now.get().plusMillis(999));
        assertThat(send(op, submit("8801511111111", "6", 0)).commandStatus()).as("still within the sliding second")
                .isEqualTo(CommandStatus.ESME_RTHROTTLED.code());
        now.set(now.get().plusMillis(1));
        assertThat(send(op, submit("8801511111111", "7", 0)).commandStatus()).as("first submit aged out").isZero();
        assertThat(metrics.snapshot().rejected()).isEqualTo(2);
        assertThat(metrics.snapshot().submitted()).isEqualTo(7);
    }

    @Test
    void windowOverflowIsRejectedAndRecordedWithoutConsultingRules() {
        Operator op = operator("rules:\n  - accept: { dlr: DELIVRD }");

        Operator.Outcome over = op.onWindowExceeded("app", "s1", submit("1", "too many", 1), new byte[0], 10);

        assertThat(over.commandStatus()).isEqualTo(CommandStatus.ESME_RMSGQFUL.code());
        assertThat(over.messageId()).isEmpty();
        assertThat(over.decision().rule()).isEqualTo("window");
        Message m = store.get(over.accepted().message().id()).orElseThrow();
        assertThat(m.status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(m.segments().getFirst().events().getFirst().detail()).contains("more than 10 submits outstanding");
        assertThat(timer.tasks).as("no receipt for a submit that was never accepted").isEmpty();
    }

    @Test
    void latencyAndDisconnectAreReportedAndRecorded() {
        Operator op = operator("rules:\n  - name: slow\n    latency: 300ms\n  - name: drop\n    disconnect: { after: 2 }\n"
                + "  - accept: { dlr: none }");

        Operator.Outcome first = send(op, submit("1", "a", 0));
        Operator.Outcome second = send(op, submit("1", "b", 0));
        Operator.Outcome otherSession = op.onSubmit("app", "s2", submit("1", "c", 0), new byte[0]);

        assertThat(first.latency()).isEqualTo(Duration.ofMillis(300));
        assertThat(first.disconnect()).isFalse();
        assertThat(second.disconnect()).isTrue();
        assertThat(otherSession.disconnect()).as("counted per session").isFalse();
        assertThat(events(first)).extracting(e -> e.type()).containsExactly(EventType.ACCEPTED, EventType.DLR_SKIPPED,
                EventType.DELAYED);
        assertThat(events(second)).extracting(e -> e.type()).contains(EventType.DISCONNECTED);
        assertThat(events(second).getLast().detail()).isEqualTo("connection dropped after the response (rules: slow, drop)");

        op.sessionClosed("s1");
        Operator.Outcome afterRebind = send(op, submit("1", "d", 0));
        assertThat(afterRebind.disconnect()).as("counter reset when the session closed").isFalse();
    }

    @Test
    void receiptIsTimedFromTheDelayedResponse() {
        Operator op = operator("rules:\n  - latency: 300ms\n  - accept: { dlr: DELIVRD, after: 100ms }");

        Operator.Outcome out = send(op, submit("1", "a", 1));

        assertThat(out.latency()).isEqualTo(Duration.ofMillis(300));
        assertThat(timer.tasks).hasSize(1);
        assertThat(timer.tasks.getFirst().delay()).isEqualTo(Duration.ofMillis(400));
        assertThat(lastEvent(out).detail()).isEqualTo("submit_sm_resp held for 300ms (rules: rule 1)");
        assertThat(events(out).get(1).detail()).isEqualTo("DELIVRD in 100ms after the response");
    }

    private List<io.cellophane.server.message.Event> events(Operator.Outcome out) {
        return store.findBySegment(out.accepted().segment().messageId()).orElseThrow().segments().stream()
                .filter(s -> s.messageId().equals(out.accepted().segment().messageId())).findFirst().orElseThrow()
                .events();
    }

    private io.cellophane.server.message.Event lastEvent(Operator.Outcome out) {
        return store.findBySegment(out.accepted().segment().messageId()).orElseThrow().segments().stream()
                .filter(s -> s.messageId().equals(out.accepted().segment().messageId())).findFirst().orElseThrow()
                .events().getLast();
    }
}
