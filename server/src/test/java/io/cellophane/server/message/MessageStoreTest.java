package io.cellophane.server.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.Udh;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MessageStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final List<Message> published = new ArrayList<>();
    private final MessageStore store = new MessageStore(3);
    private final Inbox inbox = new Inbox(store, new MessageListener() {
        @Override
        public void onMessage(Message message) {
            published.add(message);
        }

        @Override
        public void onCleared() {
            published.clear();
        }
    }, clock);

    private Message receive(String from, String to, String text) {
        SubmitSm pdu = SubmitSm.of(1, Address.alphanumeric(from), Address.international(to), 0, Gsm7.encode(text));
        return inbox.receive("app", "s1", pdu, PduCodec.encode(pdu));
    }

    @Test
    void decodesTextAndNotifiesListener() {
        Message m = receive("MyApp", "8801711111111", "Your OTP is 482913");

        assertThat(m.text()).isEqualTo("Your OTP is 482913");
        assertThat(m.encoding()).isEqualTo("GSM 7-bit");
        assertThat(m.id()).hasSize(26);
        assertThat(m.receivedAt()).isEqualTo(T0);
        assertThat(m.status()).isEqualTo(MessageStatus.ACCEPTED);
        assertThat(published).containsExactly(m);
        assertThat(store.get(m.id())).contains(m);
    }

    @Test
    void stripsUdhBeforeDecodingAndExposesConcatInfo() {
        byte[] userData = Udh.concat8(0x42, 3, 2).prepend(Gsm7.encode("middle part"));
        SubmitSm pdu = new SubmitSm(1, "", Address.alphanumeric("A"), Address.international("1"), 0x40, 0, 0, "",
                "", 0, 0, 0, 0, userData, List.of());

        Message m = inbox.receive("app", "s1", pdu, PduCodec.encode(pdu));

        assertThat(m.text()).isEqualTo("middle part");
        assertThat(m.concat()).contains(new Udh.Concat(0x42, 3, 2));
    }

    @Test
    void keepsBinaryPayloadsWithoutText() {
        SubmitSm pdu = SubmitSm.of(1, Address.empty(), Address.international("1"), 4, new byte[] {1, 2, 3});

        Message m = inbox.receive("app", "s1", pdu, PduCodec.encode(pdu));

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
    }

    @Test
    void filtersAndPagesNewestFirst() {
        MessageStore big = new MessageStore(100);
        Inbox in = new Inbox(big, new MessageListener() {
            @Override
            public void onMessage(Message message) {
            }

            @Override
            public void onCleared() {
            }
        }, clock);
        for (int i = 0; i < 5; i++) {
            SubmitSm pdu = SubmitSm.of(i, Address.alphanumeric(i % 2 == 0 ? "Even" : "Odd"),
                    Address.international("88017" + i), 0, Gsm7.encode("OTP " + i));
            in.receive(i < 3 ? "app" : "chaos", "s1", pdu, PduCodec.encode(pdu));
        }

        MessageStore.Page odd = big.list(new MessageQuery(null, "odd", null, null, null, 0, 10));
        assertThat(odd.total()).isEqualTo(2);
        assertThat(odd.messages()).extracting(Message::text).containsExactly("OTP 3", "OTP 1");

        MessageStore.Page to = big.list(new MessageQuery("880172", null, "otp", "app", null, 0, 10));
        assertThat(to.messages()).extracting(Message::text).containsExactly("OTP 2");

        MessageStore.Page paged = big.list(new MessageQuery(null, null, null, null, null, 1, 2));
        assertThat(paged.total()).isEqualTo(5);
        assertThat(paged.messages()).extracting(Message::text).containsExactly("OTP 3", "OTP 2");

        MessageStore.Page since = big.list(new MessageQuery(null, null, null, null, T0.plusSeconds(1), 0, 10));
        assertThat(since.total()).isZero();

        assertThatThrownBy(() -> new MessageQuery(null, null, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpSendBuildsAnEquivalentPdu() {
        Message m = inbox.receiveHttp("MyApp", "+8801711111111", "আপনার OTP");

        assertThat(m.account()).isEqualTo(Inbox.HTTP_ACCOUNT);
        assertThat(m.from()).isEqualTo(Address.alphanumeric("MyApp"));
        assertThat(m.to()).isEqualTo(Address.international("8801711111111"));
        assertThat(m.encoding()).isEqualTo("UCS-2");
        assertThat(m.text()).isEqualTo("আপনার OTP");
        assertThat(PduCodec.decode(io.netty.buffer.Unpooled.wrappedBuffer(m.rawPdu()))).isEqualTo(m.pdu());

        Message longOne = inbox.receiveHttp("1", "2", "x".repeat(400));
        assertThat(longOne.pdu().shortMessage()).isEmpty();
        assertThat(longOne.text()).hasSize(400);
    }

    @Test
    void clearEmptiesStoreAndNotifies() {
        receive("A", "1", "a");

        assertThat(inbox.clear()).isEqualTo(1);
        assertThat(store.size()).isZero();
        assertThat(published).isEmpty();
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
