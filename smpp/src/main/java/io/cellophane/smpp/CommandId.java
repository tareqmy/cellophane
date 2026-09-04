package io.cellophane.smpp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** SMPP 3.4 {@code command_id} values (spec section 5.1.2). */
public enum CommandId {
    GENERIC_NACK(0x80000000),
    BIND_RECEIVER(0x00000001),
    BIND_RECEIVER_RESP(0x80000001),
    BIND_TRANSMITTER(0x00000002),
    BIND_TRANSMITTER_RESP(0x80000002),
    QUERY_SM(0x00000003),
    QUERY_SM_RESP(0x80000003),
    SUBMIT_SM(0x00000004),
    SUBMIT_SM_RESP(0x80000004),
    DELIVER_SM(0x00000005),
    DELIVER_SM_RESP(0x80000005),
    UNBIND(0x00000006),
    UNBIND_RESP(0x80000006),
    REPLACE_SM(0x00000007),
    REPLACE_SM_RESP(0x80000007),
    CANCEL_SM(0x00000008),
    CANCEL_SM_RESP(0x80000008),
    BIND_TRANSCEIVER(0x00000009),
    BIND_TRANSCEIVER_RESP(0x80000009),
    OUTBIND(0x0000000B),
    ENQUIRE_LINK(0x00000015),
    ENQUIRE_LINK_RESP(0x80000015),
    SUBMIT_MULTI(0x00000021),
    SUBMIT_MULTI_RESP(0x80000021),
    ALERT_NOTIFICATION(0x00000102),
    DATA_SM(0x00000103),
    DATA_SM_RESP(0x80000103);

    /** Bit set in every response command id. */
    public static final int RESPONSE_BIT = 0x80000000;

    private static final Map<Integer, CommandId> BY_ID = new HashMap<>();

    static {
        for (CommandId c : values()) {
            BY_ID.put(c.id, c);
        }
    }

    private final int id;

    CommandId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isResponse() {
        return isResponse(id);
    }

    public static boolean isResponse(int commandId) {
        return (commandId & RESPONSE_BIT) != 0;
    }

    public boolean isBind() {
        return this == BIND_RECEIVER || this == BIND_TRANSMITTER || this == BIND_TRANSCEIVER;
    }

    /** The response command id paired with this request, e.g. {@code SUBMIT_SM -> SUBMIT_SM_RESP}. */
    public Optional<CommandId> response() {
        return isResponse() ? Optional.empty() : of(id | RESPONSE_BIT);
    }

    public static Optional<CommandId> of(int commandId) {
        return Optional.ofNullable(BY_ID.get(commandId));
    }

    /** Human-readable name for logs and hex dumps; falls back to hex for unknown ids. */
    public static String describe(int commandId) {
        return of(commandId).map(Enum::name).orElseGet(() -> String.format("0x%08X", commandId));
    }
}
