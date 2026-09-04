package io.cellophane.server.message;

import static org.assertj.core.api.Assertions.assertThat;

import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.MessagePdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.Udh;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ReassemblyTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
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
    private final RecordingListener listener = new RecordingListener();
    private final MessageStore store = new MessageStore(100);
    private final Inbox inbox = new Inbox(store, listener, clock);

    private static SubmitSm udhPart(String from, String to, int ref, int total, int seq, String text) {
        byte[] userData = Udh.concat8(ref, total, seq).prepend(Gsm7.encode(text));
        return new SubmitSm(seq, "", Address.alphanumeric(from), Address.international(to), MessagePdu.ESM_UDHI,
                0, 0, "", "", 0, 0, 0, 0, userData, List.of());
    }

    private static SubmitSm sarPart(int ref, int total, int seq, String text) {
        return new SubmitSm(seq, "", Address.alphanumeric("Sar"), Address.international("2"), 0, 0, 0, "", "", 0,
                0, 0, 0, Gsm7.encode(text), List.of(Tlv.ofShort(Tlv.Tag.SAR_MSG_REF_NUM, ref),
                        Tlv.ofByte(Tlv.Tag.SAR_TOTAL_SEGMENTS, total), Tlv.ofByte(Tlv.Tag.SAR_SEGMENT_SEQNUM, seq)));
    }

    private Inbox.Accepted receive(String account, SubmitSm pdu) {
        return inbox.receive(account, "s1", pdu, PduCodec.encode(pdu));
    }

    @Test
    void joinsUdhPartsArrivingOutOfOrderIntoOneMessage() {
        Inbox.Accepted second = receive("app", udhPart("A", "1", 0x42, 3, 2, " two"));
        Inbox.Accepted first = receive("app", udhPart("A", "1", 0x42, 3, 1, "one"));
        Inbox.Accepted third = receive("app", udhPart("A", "1", 0x42, 3, 3, " three"));

        assertThat(store.size()).isEqualTo(1);
        Message m = store.get(second.message().id()).orElseThrow();
        assertThat(first.message().id()).isEqualTo(m.id());
        assertThat(third.message().id()).isEqualTo(m.id());
        assertThat(m.text()).isEqualTo("one two three");
        assertThat(m.parts()).isEqualTo(3);
        assertThat(m.partsReceived()).isEqualTo(3);
        assertThat(m.isComplete()).isTrue();
        assertThat(m.concatReference()).isEqualTo(0x42);
        assertThat(m.segments()).extracting(Segment::sequence).containsExactly(1, 2, 3);
        assertThat(m.segments()).extracting(Segment::messageId)
                .containsExactly(first.segment().messageId(), second.segment().messageId(),
                        third.segment().messageId())
                .doesNotHaveDuplicates();

        assertThat(listener.created).hasSize(1);
        assertThat(listener.updated).extracting(Message::partsReceived).containsExactly(2, 3);
        assertThat(listener.updated.getFirst().text()).as("partial text while incomplete").isEqualTo("one two");
    }

    @Test
    void joinsSarTlvParts() {
        receive("app", sarPart(7, 2, 1, "left "));
        receive("app", sarPart(7, 2, 2, "right"));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.list(MessageQuery.all(10)).messages().getFirst().text()).isEqualTo("left right");
    }

    @Test
    void keepsPartsApartWhenReferenceSenderRecipientOrAccountDiffer() {
        receive("app", udhPart("A", "1", 1, 2, 1, "a1"));
        receive("app", udhPart("A", "1", 2, 2, 1, "different reference"));
        receive("app", udhPart("B", "1", 1, 2, 1, "different sender"));
        receive("app", udhPart("A", "9", 1, 2, 1, "different recipient"));
        receive("chaos", udhPart("A", "1", 1, 2, 1, "different account"));

        assertThat(store.size()).isEqualTo(5);
        assertThat(store.list(MessageQuery.all(10)).messages()).allMatch(m -> !m.isComplete());
    }

    @Test
    void duplicatePartStartsANewMessage() {
        Message m = receive("app", udhPart("A", "1", 1, 2, 1, "x")).message();
        Message dup = receive("app", udhPart("A", "1", 1, 2, 1, "x again")).message();

        assertThat(dup.id()).isNotEqualTo(m.id());
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void partsAfterTheWindowStartANewMessage() {
        receive("app", udhPart("A", "1", 1, 2, 1, "old"));
        now.set(T0.plus(Inbox.REASSEMBLY_WINDOW).plusSeconds(1));

        Message late = receive("app", udhPart("A", "1", 1, 2, 2, "late")).message();

        assertThat(store.size()).isEqualTo(2);
        assertThat(late.partsReceived()).isEqualTo(1);
        assertThat(late.segments().getFirst().sequence()).isEqualTo(2);
    }

    @Test
    void completedReferenceCanBeReusedForANewMessage() {
        receive("app", udhPart("A", "1", 1, 2, 1, "first "));
        receive("app", udhPart("A", "1", 1, 2, 2, "message"));
        Message next = receive("app", udhPart("A", "1", 1, 2, 1, "second ")).message();

        assertThat(store.size()).isEqualTo(2);
        assertThat(next.partsReceived()).isEqualTo(1);
    }

    @Test
    void updatesKeepTheMessagePositionAndClearDropsPendingState() {
        receive("app", udhPart("A", "1", 1, 2, 1, "multi "));
        receive("app", SubmitSm.of(9, Address.alphanumeric("S"), Address.international("3"), 0, Gsm7.encode("single")));
        receive("app", udhPart("A", "1", 1, 2, 2, "part"));

        assertThat(store.list(MessageQuery.all(10)).messages()).extracting(Message::text)
                .containsExactly("single", "multi part");

        inbox.clear();
        Message afterClear = receive("app", udhPart("A", "1", 1, 2, 2, "orphan")).message();
        assertThat(afterClear.partsReceived()).isEqualTo(1);
    }

    @Test
    void binaryPartMakesWholeTextBinary() {
        byte[] userData = Udh.concat8(5, 2, 1).prepend(new byte[] {1, 2, 3});
        receive("app", new SubmitSm(1, "", Address.alphanumeric("A"), Address.international("1"),
                MessagePdu.ESM_UDHI, 0, 0, "", "", 0, 0, 4, 0, userData, List.of()));

        assertThat(store.list(MessageQuery.all(10)).messages().getFirst().text()).isNull();
    }
}
