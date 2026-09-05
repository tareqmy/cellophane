package io.cellophane.server.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.text.Gsm7;
import io.netty.buffer.Unpooled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class MessageStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final RecordingListener listener = new RecordingListener();
    private final MessageStore store = new MessageStore(3);
    private final Inbox inbox = new Inbox(store, listener, clock);

    private Message receive(String from, String to, String text) {
        SubmitSm pdu = SubmitSm.of(1, Address.alphanumeric(from), Address.international(to), 0, Gsm7.encode(text));
        return inbox.receive("app", "s1", pdu, PduCodec.encode(pdu)).message();
    }

    @Test
    void decodesTextAndNotifiesListener() {
        Message m = receive("MyApp", "8801711111111", "Your OTP is 482913");

        assertThat(m.text()).isEqualTo("Your OTP is 482913");
        assertThat(m.encoding()).isEqualTo("GSM 7-bit");
        assertThat(m.id()).hasSize(26);
        assertThat(m.receivedAt()).isEqualTo(T0);
        assertThat(m.parts()).isEqualTo(1);
        assertThat(m.segments()).hasSize(1);
        assertThat(m.segments().getFirst().messageId()).isEqualTo(m.id());
        assertThat(m.status()).isEqualTo(MessageStatus.ACCEPTED);
        assertThat(listener.created).containsExactly(m);
        assertThat(store.get(m.id())).contains(m);
    }

    @Test
    void keepsBinaryPayloadsWithoutText() {
        SubmitSm pdu = SubmitSm.of(1, Address.empty(), Address.international("1"), 4, new byte[] {1, 2, 3});

        Message m = inbox.receive("app", "s1", pdu, PduCodec.encode(pdu)).message();

        assertThat(m.text()).isNull();
        assertThat(m.encoding()).isEqualTo("binary");
    }

    @Test
    void evictsOldestWhenFull() {
        Message a = receive("A", "1", "a");
        receive("B", "2", "b");
        receive("C", "3", "c");
        Message d = receive("D", "4", "d");

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.get(a.id())).isEmpty();
        assertThat(store.get(d.id())).isPresent();
        assertThat(store.list(MessageQuery.all(10)).messages()).extracting(Message::text)
                .containsExactly("d", "c", "b");
        assertThat(store.update(a)).as("evicted messages cannot be updated").isFalse();
    }

    @Test
    void filtersAndPagesNewestFirst() {
        MessageStore big = new MessageStore(100);
        Inbox in = new Inbox(big, new RecordingListener(), clock);
        for (int i = 0; i < 5; i++) {
            SubmitSm pdu = SubmitSm.of(i, Address.alphanumeric(i % 2 == 0 ? "Even" : "Odd"),
                    Address.international("88017" + i), 0, Gsm7.encode("OTP " + i));
            in.receive(i < 3 ? "app" : "chaos", "s1", pdu, PduCodec.encode(pdu));
        }

        MessageStore.Page odd = big.list(new MessageQuery(null, null, "odd", null, null, null, null, 0, 10));
        assertThat(odd.total()).isEqualTo(2);
        assertThat(odd.messages()).extracting(Message::text).containsExactly("OTP 3", "OTP 1");

        MessageStore.Page to = big.list(new MessageQuery(null, "880172", null, "otp", "app", null, null, 0, 10));
        assertThat(to.messages()).extracting(Message::text).containsExactly("OTP 2");

        MessageStore.Page any = big.list(new MessageQuery("even", null, null, null, null, null, null, 0, 10));
        assertThat(any.total()).isEqualTo(3);
        assertThat(big.list(new MessageQuery("880174", null, null, null, null, null, null, 0, 10)).total()).isEqualTo(1);
        assertThat(big.list(new MessageQuery("otp 1", null, null, null, null, null, null, 0, 10)).total()).isEqualTo(1);

        MessageStore.Page paged = big.list(new MessageQuery(null, null, null, null, null, null, null, 1, 2));
        assertThat(paged.total()).isEqualTo(5);
        assertThat(paged.messages()).extracting(Message::text).containsExactly("OTP 3", "OTP 2");

        MessageStore.Page since = big.list(new MessageQuery(null, null, null, null, null, null, T0.plusSeconds(1), 0, 10));
        assertThat(since.total()).isZero();

        assertThatThrownBy(() -> new MessageQuery(null, null, null, null, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersByStatus() {
        MessageStore big = new MessageStore(100);
        Inbox in = new Inbox(big, new RecordingListener(), clock);
        SubmitSm ok = SubmitSm.of(1, Address.alphanumeric("A"), Address.international("1"), 0, Gsm7.encode("ok"));
        SubmitSm bad = SubmitSm.of(2, Address.alphanumeric("A"), Address.international("1"), 0, Gsm7.encode("bad"));
        in.receive("app", "s1", ok, PduCodec.encode(ok));
        Inbox.Accepted rejected = in.receive("app", "s1", bad, PduCodec.encode(bad), Inbox.decode(bad),
                MessageStatus.REJECTED, new Event(T0, EventType.REJECTED, "test"));
        in.record(rejected.segment().messageId(), EventType.DLR_SIMULATED, "x", null);

        assertThat(big.list(new MessageQuery(null, null, null, null, null, java.util.Set.of(MessageStatus.REJECTED),
                null, 0, 10)).messages()).extracting(Message::text).containsExactly("bad");
        assertThat(big.list(new MessageQuery(null, null, null, null, null, java.util.Set.of(MessageStatus.ACCEPTED,
                MessageStatus.DELIVRD), null, 0, 10)).messages()).extracting(Message::text).containsExactly("ok");
        assertThat(big.list(new MessageQuery(null, null, null, null, null, java.util.Set.of(), null, 0, 10)).total())
                .as("empty set means no filter").isEqualTo(2);
    }

    @Test
    void httpSendBuildsAnEquivalentPdu() {
        Message m = inbox.receiveHttp("MyApp", "+8801711111111", "আপনার OTP");

        assertThat(m.account()).isEqualTo(Inbox.HTTP_ACCOUNT);
        assertThat(m.from()).isEqualTo(Address.alphanumeric("MyApp"));
        assertThat(m.to()).isEqualTo(Address.international("8801711111111"));
        assertThat(m.encoding()).isEqualTo("UCS-2");
        assertThat(m.text()).isEqualTo("আপনার OTP");
        Segment segment = m.segments().getFirst();
        assertThat(PduCodec.decode(Unpooled.wrappedBuffer(segment.rawPdu()))).isEqualTo(segment.pdu());

        Message longOne = inbox.receiveHttp("1", "2", "x".repeat(400));
        assertThat(longOne.segments().getFirst().pdu().shortMessage()).isEmpty();
        assertThat(longOne.text()).hasSize(400);
    }

    @Test
    void clearEmptiesStoreAndNotifies() {
        receive("A", "1", "a");

        assertThat(inbox.clear()).isEqualTo(1);
        assertThat(store.size()).isZero();
        assertThat(listener.cleared).isEqualTo(1);
    }

    @Test
    void parsesSinceValues() {
        assertThat(Since.parse("30s", clock)).isEqualTo(T0.minus(Duration.ofSeconds(30)));
        assertThat(Since.parse("5m", clock)).isEqualTo(T0.minus(Duration.ofMinutes(5)));
        assertThat(Since.parse("2h", clock)).isEqualTo(T0.minus(Duration.ofHours(2)));
        assertThat(Since.parse("1d", clock)).isEqualTo(T0.minus(Duration.ofDays(1)));
        assertThat(Since.parse("2026-09-04T09:00:00Z", clock)).isEqualTo(T0.minus(Duration.ofHours(1)));
        assertThatThrownBy(() -> Since.parse("yesterday", clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
