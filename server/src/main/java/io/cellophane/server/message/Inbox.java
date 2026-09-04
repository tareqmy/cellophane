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
import java.time.Instant;
import java.util.List;

/** Turns accepted submits into stored, decoded messages and tells listeners about them. */
public final class Inbox {

    /** Account name recorded for messages that arrive over the HTTP send endpoint rather than SMPP. */
    public static final String HTTP_ACCOUNT = "http";

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    private final MessageStore store;
    private final MessageListener listener;
    private final Clock clock;

    public Inbox(MessageStore store, MessageListener listener, Clock clock) {
        this.store = store;
        this.listener = listener;
        this.clock = clock;
    }

    /** Decodes and stores a submit_sm the operator accepted. */
    public Message receive(String account, String sessionId, SubmitSm pdu, byte[] rawPdu) {
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
        DataCoding.Alphabet alphabet = DataCoding.alphabet(pdu.dataCoding());
        String text = SmsText.decode(pdu.dataCoding(), body).orElse(null);
        Instant now = clock.instant();
        Message message = new Message(Ids.newId(now.toEpochMilli()), now, account, sessionId, pdu, rawPdu, text,
                alphabet.label(), udh, MessageStatus.ACCEPTED);
        store.add(message);
        log.debug("[{}] {} -> {} ({}): {}", sessionId, message.from().address(), message.to().address(),
                alphabet.label(), text);
        listener.onMessage(message);
        return message;
    }

    /** Builds an equivalent submit_sm for a message sent over HTTP and stores it like any other. */
    public Message receiveHttp(String from, String to, String text) {
        int dataCoding = SmsText.chooseDataCoding(text);
        byte[] userData = SmsText.encode(dataCoding, text);
        byte[] shortMessage = userData.length <= 255 ? userData : new byte[0];
        List<Tlv> tlvs = userData.length <= 255 ? List.of() : List.of(new Tlv(Tlv.Tag.MESSAGE_PAYLOAD, userData));
        SubmitSm pdu = new SubmitSm(0, "", address(from), address(to), 0, 0, 0, "", "", 0, 0, dataCoding, 0,
                shortMessage, tlvs);
        return receive(HTTP_ACCOUNT, HTTP_ACCOUNT, pdu, PduCodec.encode(pdu));
    }

    public int clear() {
        int dropped = store.clear();
        listener.onCleared();
        return dropped;
    }

    private static Address address(String value) {
        String v = value.startsWith("+") ? value.substring(1) : value;
        boolean numeric = !v.isEmpty() && v.chars().allMatch(Character::isDigit);
        return numeric ? Address.international(v) : Address.alphanumeric(value);
    }
}
