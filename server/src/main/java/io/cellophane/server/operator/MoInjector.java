package io.cellophane.server.operator;

import io.cellophane.server.smpp.SessionRegistry;
import io.cellophane.server.smpp.SmppSession;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.MessagePdu;
import io.cellophane.smpp.text.Segmenter;
import io.cellophane.smpp.text.SmsText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pushes a mobile-originated message toward an ESME as deliver_sm, split into concatenated parts when it is
 * long, so a test can drive the receiving side of an application.
 */
public final class MoInjector {

    /** What was sent: to which session, how many parts, with which sequence numbers. */
    public record Sent(String account, String sessionId, int parts, List<Integer> sequenceNumbers, int dataCoding) {
    }

    /** No bound session of the account can receive deliver_sm. */
    public static final class NoReceiverException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoReceiverException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MoInjector.class);

    private final SessionRegistry sessions;
    private final Metrics metrics;
    private final AtomicInteger reference = new AtomicInteger();

    public MoInjector(SessionRegistry sessions, Metrics metrics) {
        this.sessions = sessions;
        this.metrics = metrics;
    }

    /**
     * @param account the ESME account to deliver to, or null to use the only account that has a receiver bound
     */
    public Sent inject(String account, String from, String to, String text) {
        String target = account != null ? account : soleReceivingAccount();
        SmppSession session = sessions.receiverFor(target).orElseThrow(() -> new NoReceiverException(
                "no receiver or transceiver bound for account '" + target + "'"));
        int dataCoding = SmsText.chooseDataCoding(text);
        List<Segmenter.Part> parts = Segmenter.split(text, dataCoding, reference.incrementAndGet() & 0xFF);
        List<Integer> seqs = new ArrayList<>(parts.size());
        for (Segmenter.Part part : parts) {
            int seq = session.nextSequence();
            DeliverSm pdu = new DeliverSm(seq, "", address(from), address(to), part.hasUdh() ? MessagePdu.ESM_UDHI : 0,
                    0, 0, "", "", 0, 0, dataCoding, 0, part.userData(), List.of());
            session.channel().writeAndFlush(pdu);
            seqs.add(seq);
            metrics.moSent();
        }
        log.info("[{}] injected MO {} -> {} in {} part(s)", session.id(), from, to, parts.size());
        return new Sent(target, session.id(), parts.size(), seqs, dataCoding);
    }

    private String soleReceivingAccount() {
        List<String> accounts = sessions.bound().stream().filter(SmppSession::canReceive)
                .flatMap(s -> s.account().stream()).map(a -> a.systemId()).distinct().toList();
        if (accounts.size() == 1) {
            return accounts.getFirst();
        }
        throw new NoReceiverException(accounts.isEmpty() ? "no receiver or transceiver is bound"
                : "several accounts have receivers bound (" + String.join(", ", accounts) + "); say which");
    }

    static Address address(String value) {
        String v = value.startsWith("+") ? value.substring(1) : value;
        boolean numeric = !v.isEmpty() && v.chars().allMatch(Character::isDigit);
        return numeric ? Address.international(v) : Address.alphanumeric(value);
    }

    /** For tests: the account that would be chosen when none is given. */
    Optional<String> defaultAccount() {
        try {
            return Optional.of(soleReceivingAccount());
        } catch (NoReceiverException e) {
            return Optional.empty();
        }
    }
}
