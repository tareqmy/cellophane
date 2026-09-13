package io.cellophane.server.message;

import static org.assertj.core.api.Assertions.assertThat;

import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.Udh;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageDatabaseTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00.123456Z");

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private MessageDatabase db;

    @AfterEach
    void close() {
        if (db != null) {
            db.close();
        }
    }

    private MessageDatabase open(int capacity) {
        db = new MessageDatabase(dir.resolve("inbox.db"), capacity);
        return db;
    }

    private static SubmitSm submit(int seq, String from, String to, byte[] userData, boolean udh) {
        return new SubmitSm(seq, "", Address.alphanumeric(from), Address.international(to), udh ? 0x40 : 0, 0, 0,
                "", "", 0, 0, 0, 0, userData, List.of());
    }

    @Test
    void savedMessagesComeBackWithPartsTimelinesAndStatuses() {
        MessageStore store = new MessageStore(10);
        Inbox inbox = new Inbox(store, MessageListener.all(new RecordingListener(), open(10)), clock);
        SubmitSm one = submit(1, "MyApp", "8801711111111", Gsm7.encode("Your OTP is 482913"), false);
        Inbox.Accepted single = inbox.receive("app", "s1", one, PduCodec.encode(one));
        inbox.record(single.segment().messageId(), EventType.DLR_SCHEDULED, "DELIVRD in 500ms", null);
        inbox.record(single.segment().messageId(), EventType.DLR_SENT, "deliver_sm", MessageStatus.DELIVRD);
        SubmitSm p1 = submit(2, "MyApp", "8801722222222", Udh.concat8(7, 2, 1).prepend(Gsm7.encode("first ")), true);
        SubmitSm p2 = submit(3, "MyApp", "8801722222222", Udh.concat8(7, 2, 2).prepend(Gsm7.encode("second")), true);
        inbox.receive("app", "s1", p1, PduCodec.encode(p1));
        inbox.receive("app", "s1", p2, PduCodec.encode(p2));
        SubmitSm bin = SubmitSm.of(4, Address.empty(), Address.international("1"), 4, new byte[] {1, 2, 3});
        inbox.receive("app", "s2", bin, PduCodec.encode(bin), Inbox.decode(bin), MessageStatus.REJECTED,
                new Event(T0, EventType.REJECTED, "submit_sm_resp ESME_RINVDSTADR"));
        db.flush();
        List<Message> saved = store.list(MessageQuery.all(10)).messages();
        db.close();

        MessageStore restored = new MessageStore(10);
        int loaded = open(10).restore(restored);

        assertThat(loaded).isEqualTo(3);
        assertThat(restored.list(MessageQuery.all(10)).messages()).usingRecursiveComparison().isEqualTo(saved);
        Message concat = restored.get(saved.get(1).id()).orElseThrow();
        assertThat(concat.text()).isEqualTo("first second");
        assertThat(concat.segments()).hasSize(2);
        assertThat(concat.segments().get(1).udh()).isNotNull();
        assertThat(restored.findBySegment(concat.segments().get(1).messageId())).contains(concat);
        Message otp = restored.get(single.message().id()).orElseThrow();
        assertThat(otp.status()).isEqualTo(MessageStatus.DELIVRD);
        assertThat(otp.segments().getFirst().events()).extracting(Event::type)
                .containsExactly(EventType.ACCEPTED, EventType.DLR_SCHEDULED, EventType.DLR_SENT);
        assertThat(otp.segments().getFirst().events().getFirst().at()).isEqualTo(T0);
    }

    @Test
    void keepsOnlyTheNewestMessagesUpToCapacity() {
        MessageStore store = new MessageStore(3);
        Inbox inbox = new Inbox(store, open(3), clock);
        for (int i = 0; i < 20; i++) {
            SubmitSm pdu = submit(i, "A", "1", Gsm7.encode("m" + i), false);
            inbox.receive("app", "s1", pdu, PduCodec.encode(pdu));
        }
        db.flush();

        assertThat(db.counts()).containsEntry("messages", 3).containsEntry("segments", 3).containsEntry("events", 3);
        MessageStore restored = new MessageStore(3);
        db.restore(restored);
        assertThat(restored.list(MessageQuery.all(10)).messages()).extracting(Message::text)
                .containsExactly("m19", "m18", "m17");
    }

    @Test
    void clearEmptiesTheFileAndRestoreLoadsNothing() {
        MessageStore store = new MessageStore(10);
        Inbox inbox = new Inbox(store, open(10), clock);
        SubmitSm pdu = submit(1, "A", "1", Gsm7.encode("gone"), false);
        inbox.receive("app", "s1", pdu, PduCodec.encode(pdu));
        db.flush();
        assertThat(db.counts()).containsEntry("messages", 1);

        inbox.clear();

        assertThat(db.counts()).containsEntry("messages", 0).containsEntry("segments", 0).containsEntry("events", 0);
        assertThat(db.restore(new MessageStore(10))).isZero();
    }

    @Test
    void restoreRespectsASmallerCapacityThanTheFileHolds() {
        Inbox inbox = new Inbox(new MessageStore(10), open(10), clock);
        for (int i = 0; i < 5; i++) {
            SubmitSm pdu = submit(i, "A", "1", Gsm7.encode("m" + i), false);
            inbox.receive("app", "s1", pdu, PduCodec.encode(pdu));
        }
        db.flush();
        db.close();

        MessageStore small = new MessageStore(2);
        assertThat(open(2).restore(small)).isEqualTo(2);
        assertThat(small.list(MessageQuery.all(10)).messages()).extracting(Message::text).containsExactly("m4", "m3");
    }
}
