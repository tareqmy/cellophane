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
 * Turns accepted (or rejected) submits into stored, decoded messages, reassembling concatenated parts, records
 * what later happens to each part, and tells listeners. Parts are matched on account, sender, recipient and
 * concat reference, from either a UDH or the SAR TLVs.
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

    /** The outcome of storing one submit: the logical message and the part it became. */
    public record Accepted(Message message, Segment segment) {
    }

    /** What the codec makes of a submit's user data. */
    public record Decoded(String text, String encoding, Udh udh, Optional<Udh.Concat> concat) {
    }

    private record ConcatKey(String account, String from, String to, int reference, int total) {
    }

    public Inbox(MessageStore store, MessageListener listener, Clock clock) {
        this.store = store;
        this.listener = listener;
        this.clock = clock;
    }

    /** Decodes text, UDH and concatenation info without storing anything. */
    public static Decoded decode(SubmitSm pdu) {
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
        return new Decoded(text, encoding, udh, concatOf(pdu, udh));
    }

    /** Stores a submit as accepted with no further history; used where no operator decision is involved. */
    public Accepted receive(String account, String sessionId, SubmitSm pdu, byte[] rawPdu) {
        return receive(account, sessionId, pdu, rawPdu, decode(pdu), MessageStatus.ACCEPTED,
                new Event(clock.instant(), EventType.ACCEPTED, "submit_sm_resp ESME_ROK"));
    }

    /** Stores a submit with the operator's verdict as the first timeline entry. */
    public synchronized Accepted receive(String account, String sessionId, SubmitSm pdu, byte[] rawPdu,
                                         Decoded decoded, MessageStatus status, Event first) {
        Instant now = clock.instant();
        Optional<Udh.Concat> concat = decoded.concat();
        Segment segment = new Segment(Ids.newId(now.toEpochMilli()), concat.map(Udh.Concat::sequence).orElse(1),
                now, sessionId, pdu, rawPdu, decoded.udh(), decoded.text(), status, List.of(first));

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
            Message message = Message.of(account, decoded.encoding(), c.reference(), c.total(), segment);
            store.add(message);
            if (message.isComplete()) {
                pending.remove(key);
            } else {
                pending.put(key, message.id());
            }
            listener.onMessage(message);
            return new Accepted(message, segment);
        }

        Message message = Message.of(account, decoded.encoding(), null, 1, segment);
        store.add(message);
        log.debug("[{}] {} -> {} ({}): {}", sessionId, message.from().address(), message.to().address(),
                decoded.encoding(), decoded.text());
        listener.onMessage(message);
        return new Accepted(message, segment);
    }

    /**
     * Appends an event to a part's timeline, optionally moving it to a new status. Silently ignored if the
     * message has since been evicted or cleared.
     */
    public synchronized Optional<Message> record(String segmentMessageId, EventType type, String detail,
                                                 MessageStatus newStatus) {
        Instant now = clock.instant();
        Optional<Message> found = store.findBySegment(segmentMessageId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Message message = found.get();
        Segment segment = message.segments().stream().filter(s -> s.messageId().equals(segmentMessageId))
                .findFirst().orElseThrow();
        Message updated = message.withSegmentReplaced(segment.with(new Event(now, type, detail), newStatus), now);
        if (!store.update(updated)) {
            return Optional.empty();
        }
        listener.onUpdated(updated);
        return Optional.of(updated);
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

    /** Builds the submit_sm an HTTP send is equivalent to, so it can go through the operator like any other. */
    public static SubmitSm httpSubmit(String from, String to, String text) {
        int dataCoding = SmsText.chooseDataCoding(text);
        byte[] userData = SmsText.encode(dataCoding, text);
        byte[] shortMessage = userData.length <= 255 ? userData : new byte[0];
        List<Tlv> tlvs = userData.length <= 255 ? List.of() : List.of(new Tlv(Tlv.Tag.MESSAGE_PAYLOAD, userData));
        return new SubmitSm(0, "", address(from), address(to), 0, 0, 0, "", "", 1, 0, dataCoding, 0, shortMessage,
                tlvs);
    }

    /** Stores an HTTP send as plainly accepted, bypassing the operator's rules. */
    public Message receiveHttp(String from, String to, String text) {
        SubmitSm pdu = httpSubmit(from, to, text);
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
