package io.cellophane.server.message;

import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.text.DataCoding;
import io.cellophane.smpp.text.SmsText;
import io.cellophane.smpp.text.Udh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns accepted submits into stored, decoded messages, reassembling concatenated parts, and tells listeners.
 * Parts are matched on account, sender, recipient and concat reference, from either a UDH or the SAR TLVs.
 */
public final class Inbox {

    /** Account name recorded for messages that arrive over the HTTP send endpoint rather than SMPP. */
    public static final String HTTP_ACCOUNT = "http";

    /** Parts arriving later than this after the first are treated as a new message. */
    static final Duration REASSEMBLY_WINDOW = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);
    private static final int MAX_PENDING = 10_000;

    private final MessageStore store;
    private final MessageListener listener;
    private final Clock clock;
    private final Map<ConcatKey, String> pending = new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<ConcatKey, String> eldest) {
            return size() > MAX_PENDING;
        }
    };

    /** The outcome of accepting one submit: the logical message and the part it became. */
    public record Accepted(Message message, Segment segment) {
    }

    private record ConcatKey(String account, String from, String to, int reference, int total) {
    }

    public Inbox(MessageStore store, MessageListener listener, Clock clock) {
        this.store = store;
        this.listener = listener;
        this.clock = clock;
    }

    /** Decodes and stores a submit_sm the operator accepted. */
    public synchronized Accepted receive(String account, String sessionId, SubmitSm pdu, byte[] rawPdu) {
        byte[] payload = pdu.payload();
        byte[] body = payload;
        Udh udh = null;
        if (pdu.hasUdh()) {
            try {
                udh = Udh.parse(payload);
                body = udh.body(payload);
            } catch (IllegalArgumentException e) {
                log.debug("UDHI set but header unreadable: {}", e.getMessage());
            }
        }
        String encoding = DataCoding.alphabet(pdu.dataCoding()).label();
        String text = SmsText.decode(pdu.dataCoding(), body).orElse(null);
        Instant now = clock.instant();
        Optional<Udh.Concat> concat = concatOf(pdu, udh);
        Segment segment = new Segment(Ids.newId(now.toEpochMilli()), concat.map(Udh.Concat::sequence).orElse(1),
                now, sessionId, pdu, rawPdu, udh, text);

        if (concat.isPresent()) {
            Udh.Concat c = concat.get();
            ConcatKey key = new ConcatKey(account, pdu.source().address(), pdu.destination().address(),
                    c.reference(), c.total());
            Message existing = Optional.ofNullable(pending.get(key)).flatMap(store::get).orElse(null);
            if (existing != null && !existing.isComplete() && !existing.hasSegment(c.sequence())
                    && now.isBefore(existing.receivedAt().plus(REASSEMBLY_WINDOW))) {
                Message updated = existing.withSegment(segment);
                if (store.update(updated)) {
                    if (updated.isComplete()) {
                        pending.remove(key);
                    }
                    log.debug("[{}] part {}/{} of {} from {}", sessionId, c.sequence(), c.total(), updated.id(),
                            updated.from().address());
                    listener.onUpdated(updated);
                    return new Accepted(updated, segment);
                }
            }
            Message message = Message.of(account, encoding, c.reference(), c.total(), segment);
            store.add(message);
            if (message.isComplete()) {
                pending.remove(key);
            } else {
                pending.put(key, message.id());
            }
            listener.onMessage(message);
            return new Accepted(message, segment);
        }

        Message message = Message.of(account, encoding, null, 1, segment);
        store.add(message);
        log.debug("[{}] {} -> {} ({}): {}", sessionId, message.from().address(), message.to().address(), encoding,
                text);
        listener.onMessage(message);
        return new Accepted(message, segment);
    }

    /** Concatenation info from the UDH, or failing that from the SAR optional parameters. */
    static Optional<Udh.Concat> concatOf(SubmitSm pdu, Udh udh) {
        if (udh != null && udh.concat().isPresent()) {
            return udh.concat();
        }
        Optional<Tlv> ref = pdu.tlv(Tlv.Tag.SAR_MSG_REF_NUM);
        Optional<Tlv> total = pdu.tlv(Tlv.Tag.SAR_TOTAL_SEGMENTS);
        Optional<Tlv> seq = pdu.tlv(Tlv.Tag.SAR_SEGMENT_SEQNUM);
        if (ref.isPresent() && total.isPresent() && seq.isPresent()
                && ref.get().length() == 2 && total.get().length() == 1 && seq.get().length() == 1) {
            try {
                return Optional.of(new Udh.Concat(ref.get().asShort(), total.get().asByte(), seq.get().asByte()));
            } catch (IllegalArgumentException e) {
                log.debug("ignoring inconsistent SAR parameters: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Builds an equivalent submit_sm for a message sent over HTTP and stores it like any other. */
    public Message receiveHttp(String from, String to, String text) {
        int dataCoding = SmsText.chooseDataCoding(text);
        byte[] userData = SmsText.encode(dataCoding, text);
        byte[] shortMessage = userData.length <= 255 ? userData : new byte[0];
        List<Tlv> tlvs = userData.length <= 255 ? List.of() : List.of(new Tlv(Tlv.Tag.MESSAGE_PAYLOAD, userData));
        SubmitSm pdu = new SubmitSm(0, "", address(from), address(to), 0, 0, 0, "", "", 0, 0, dataCoding, 0,
                shortMessage, tlvs);
        return receive(HTTP_ACCOUNT, HTTP_ACCOUNT, pdu, PduCodec.encode(pdu)).message();
    }

    public synchronized int clear() {
        int dropped = store.clear();
        pending.clear();
        listener.onCleared();
        return dropped;
    }

    private static Address address(String value) {
        String v = value.startsWith("+") ? value.substring(1) : value;
        boolean numeric = !v.isEmpty() && v.chars().allMatch(Character::isDigit);
        return numeric ? Address.international(v) : Address.alphanumeric(value);
    }
}
