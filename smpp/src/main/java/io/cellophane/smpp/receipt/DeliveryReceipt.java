package io.cellophane.smpp.receipt;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SMSC delivery receipt text carried in a {@code deliver_sm} (SMPP 3.4 appendix B):
 * {@code id:X sub:001 dlvrd:001 submit date:YYMMDDhhmm done date:YYMMDDhhmm stat:DELIVRD err:000 text:...}.
 *
 * @param error a three-character network error code, {@code 000} for none
 * @param text  the first characters of the original message, at most 20
 */
public record DeliveryReceipt(String id, int submitted, int delivered, LocalDateTime submitDate,
                              LocalDateTime doneDate, State state, String error, String text) {

    /** Final and intermediate message states with their {@code message_state} TLV codes and 7-char stat tokens. */
    public enum State {
        ENROUTE(1, "ENROUTE"),
        DELIVERED(2, "DELIVRD"),
        EXPIRED(3, "EXPIRED"),
        DELETED(4, "DELETED"),
        UNDELIVERABLE(5, "UNDELIV"),
        ACCEPTED(6, "ACCEPTD"),
        UNKNOWN(7, "UNKNOWN"),
        REJECTED(8, "REJECTD");

        private final int code;
        private final String stat;

        State(int code, String stat) {
            this.code = code;
            this.stat = stat;
        }

        /** Value for the {@code message_state} optional parameter. */
        public int code() {
            return code;
        }

        /** The {@code stat:} token in the receipt text. */
        public String stat() {
            return stat;
        }

        public boolean isFinal() {
            return this != ENROUTE && this != ACCEPTED && this != UNKNOWN;
        }

        /** Looks a state up by stat token or enum name, case-insensitively. */
        public static Optional<State> of(String token) {
            String t = token.trim().toUpperCase(Locale.ROOT);
            for (State s : values()) {
                if (s.stat.equals(t) || s.name().equals(t)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }

        public static Optional<State> ofCode(int code) {
            for (State s : values()) {
                if (s.code == code) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }
    }

    public static final int MAX_TEXT = 20;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMddHHmm");
    private static final Pattern FIELD = Pattern.compile(
            "id:(?<id>\\S*)\\s+sub:(?<sub>\\d+)\\s+dlvrd:(?<dlvrd>\\d+)\\s+submit date:(?<submit>\\d{10,12})"
            + "\\s+done date:(?<done>\\d{10,12})\\s+stat:(?<stat>\\w+)\\s+err:(?<err>\\w+)(?:\\s+text:(?<text>.*))?",
            Pattern.DOTALL);

    public DeliveryReceipt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(submitDate, "submitDate");
        Objects.requireNonNull(doneDate, "doneDate");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(error, "error");
        text = text == null ? "" : text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    /** A receipt for a single-part message that reached the given state now. */
    public static DeliveryReceipt of(String id, LocalDateTime submitDate, LocalDateTime doneDate, State state,
                                     String originalText) {
        boolean delivered = state == State.DELIVERED;
        return new DeliveryReceipt(id, 1, delivered ? 1 : 0, submitDate, doneDate, state, delivered ? "000" : "001",
                originalText);
    }

    public String format() {
        return "id:" + id + " sub:" + pad(submitted) + " dlvrd:" + pad(delivered)
                + " submit date:" + DATE.format(submitDate) + " done date:" + DATE.format(doneDate)
                + " stat:" + state.stat() + " err:" + error + " text:" + text;
    }

    private static String pad(int n) {
        return String.format("%03d", n);
    }

    /** Parses receipt text; empty when it does not follow the standard layout. */
    public static Optional<DeliveryReceipt> parse(String receipt) {
        Matcher m = FIELD.matcher(receipt.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<State> state = State.of(m.group("stat"));
        if (state.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DeliveryReceipt(m.group("id"), Integer.parseInt(m.group("sub")),
                    Integer.parseInt(m.group("dlvrd")), parseDate(m.group("submit")), parseDate(m.group("done")),
                    state.get(), m.group("err"), m.group("text") == null ? "" : m.group("text")));
        } catch (DateTimeParseException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static LocalDateTime parseDate(String v) {
        return LocalDateTime.parse(v.length() == 12 ? v.substring(0, 10) : v, DATE);
    }
}
