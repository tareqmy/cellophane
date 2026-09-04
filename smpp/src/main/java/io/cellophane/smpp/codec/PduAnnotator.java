package io.cellophane.smpp.codec;

import io.cellophane.smpp.CommandId;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.Smpp;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.text.DataCoding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Explains a PDU byte by byte: which bytes are which field and what they mean. Unlike {@link PduCodec} this never
 * throws; a malformed or truncated PDU is annotated as far as it goes and then marked.
 */
public final class PduAnnotator {

    /** One field of the PDU: the byte range it occupies and a human-readable value. */
    public record Field(int offset, int length, String name, String value) {
    }

    private static final HexFormat HEX = HexFormat.of();
    private static final String[] TON = {"unknown", "international", "national", "network specific",
            "subscriber number", "alphanumeric", "abbreviated"};

    private PduAnnotator() {
    }

    public static List<Field> annotate(byte[] pdu) {
        Cursor c = new Cursor(pdu);
        try {
            c.u32("command_length", v -> v + " bytes" + (v == pdu.length ? "" : " (actual " + pdu.length + ")"));
            int commandId = c.u32("command_id", v -> String.format("0x%08X %s", v, commandName(v)));
            c.u32("command_status", v -> String.format("0x%08X %s", v, CommandStatus.of(v)
                    .map(s -> s.name() + " (" + s.description() + ")").orElse("vendor specific")));
            c.u32("sequence_number", v -> Integer.toUnsignedString(v));
            body(c, commandId);
        } catch (Truncated t) {
            c.fields.add(new Field(c.pos, pdu.length - c.pos, "truncated",
                    "PDU ends inside " + t.getMessage()));
            return c.fields;
        }
        if (c.pos < pdu.length) {
            c.fields.add(new Field(c.pos, pdu.length - c.pos, "trailing bytes",
                    HEX.formatHex(pdu, c.pos, pdu.length)));
        }
        return c.fields;
    }

    private static void body(Cursor c, int commandId) {
        Optional<CommandId> known = CommandId.of(commandId);
        if (known.isEmpty()) {
            c.rest("body");
            return;
        }
        switch (known.get()) {
            case BIND_RECEIVER, BIND_TRANSMITTER, BIND_TRANSCEIVER -> {
                c.cstr("system_id");
                c.cstr("password");
                c.cstr("system_type");
                c.u8("interface_version", v -> String.format("0x%02X (SMPP %d.%d)", v, v >> 4, v & 0x0F));
                c.u8("addr_ton", PduAnnotator::ton);
                c.u8("addr_npi", PduAnnotator::npi);
                c.cstr("address_range");
                c.tlvs();
            }
            case BIND_RECEIVER_RESP, BIND_TRANSMITTER_RESP, BIND_TRANSCEIVER_RESP -> {
                if (c.remaining() > 0) {
                    c.cstr("system_id");
                    c.tlvs();
                }
            }
            case SUBMIT_SM, DELIVER_SM -> {
                c.cstr("service_type");
                c.u8("source_addr_ton", PduAnnotator::ton);
                c.u8("source_addr_npi", PduAnnotator::npi);
                c.cstr("source_addr");
                c.u8("dest_addr_ton", PduAnnotator::ton);
                c.u8("dest_addr_npi", PduAnnotator::npi);
                c.cstr("destination_addr");
                c.u8("esm_class", PduAnnotator::esmClass);
                c.u8("protocol_id", Integer::toString);
                c.u8("priority_flag", v -> v + (v == 0 ? " (lowest)" : v == 3 ? " (highest)" : ""));
                c.cstr("schedule_delivery_time");
                c.cstr("validity_period");
                c.u8("registered_delivery", PduAnnotator::registeredDelivery);
                c.u8("replace_if_present_flag", v -> v == 0 ? "0 (no)" : v + " (replace)");
                c.u8("data_coding", v -> String.format("0x%02X %s", v, DataCoding.alphabet(v).label()));
                c.u8("sm_default_msg_id", Integer::toString);
                int length = c.u8("sm_length", v -> v + " bytes");
                c.bytes("short_message", length);
                c.tlvs();
            }
            case SUBMIT_SM_RESP -> {
                if (c.remaining() > 0) {
                    c.cstr("message_id");
                    c.tlvs();
                }
            }
            case DELIVER_SM_RESP -> {
                if (c.remaining() > 0) {
                    c.cstr("message_id");
                    c.tlvs();
                }
            }
            case UNBIND, UNBIND_RESP, ENQUIRE_LINK, ENQUIRE_LINK_RESP, GENERIC_NACK -> { }
            default -> c.rest("body");
        }
    }

    private static String commandName(int id) {
        return CommandId.of(id).map(c -> c.name().toLowerCase()).orElse("unknown command");
    }

    private static String ton(int v) {
        return v + (v < TON.length ? " (" + TON[v] + ")" : " (reserved)");
    }

    private static String npi(int v) {
        String name = switch (v) {
            case 0 -> "unknown";
            case 1 -> "ISDN (E.163/E.164)";
            case 3 -> "data (X.121)";
            case 4 -> "telex (F.69)";
            case 6 -> "land mobile (E.212)";
            case 8 -> "national";
            case 9 -> "private";
            case 10 -> "ERMES";
            case 14 -> "internet (IP)";
            case 18 -> "WAP client id";
            default -> "reserved";
        };
        return v + " (" + name + ")";
    }

    private static String esmClass(int v) {
        List<String> parts = new ArrayList<>();
        switch (v & 0x03) {
            case 1 -> parts.add("datagram mode");
            case 2 -> parts.add("forward mode");
            case 3 -> parts.add("store and forward");
            default -> { }
        }
        switch (v & 0x3C) {
            case 0x04 -> parts.add("delivery receipt");
            case 0x08 -> parts.add("delivery acknowledgement");
            case 0x10 -> parts.add("manual user acknowledgement");
            case 0x18 -> parts.add("conversation abort");
            case 0x20 -> parts.add("intermediate delivery notification");
            default -> { }
        }
        if ((v & 0x40) != 0) {
            parts.add("UDHI (user data header present)");
        }
        if ((v & 0x80) != 0) {
            parts.add("reply path");
        }
        return String.format("0x%02X%s", v, parts.isEmpty() ? " (default)" : " " + String.join(", ", parts));
    }

    private static String registeredDelivery(int v) {
        List<String> parts = new ArrayList<>();
        switch (v & 0x03) {
            case 1 -> parts.add("final receipt requested");
            case 2 -> parts.add("receipt on failure only");
            case 3 -> parts.add("reserved receipt value");
            default -> { }
        }
        switch (v & 0x0C) {
            case 0x04 -> parts.add("SME delivery ack");
            case 0x08 -> parts.add("SME manual ack");
            case 0x0C -> parts.add("SME delivery and manual ack");
            default -> { }
        }
        if ((v & 0x10) != 0) {
            parts.add("intermediate notification");
        }
        return String.format("0x%02X%s", v, parts.isEmpty() ? " (no receipt)" : " " + String.join(", ", parts));
    }

    private static String tlvValue(Tlv tlv) {
        switch (tlv.tag()) {
            case Tlv.Tag.SAR_MSG_REF_NUM, Tlv.Tag.USER_MESSAGE_REFERENCE, Tlv.Tag.SOURCE_PORT,
                 Tlv.Tag.DESTINATION_PORT -> {
                if (tlv.length() == 2) {
                    return Integer.toString(tlv.asShort());
                }
            }
            case Tlv.Tag.SAR_TOTAL_SEGMENTS, Tlv.Tag.SAR_SEGMENT_SEQNUM, Tlv.Tag.MESSAGE_STATE,
                 Tlv.Tag.SC_INTERFACE_VERSION, Tlv.Tag.MORE_MESSAGES_TO_SEND -> {
                if (tlv.length() == 1) {
                    return Integer.toString(tlv.asByte()) + (tlv.tag() == Tlv.Tag.MESSAGE_STATE
                            ? " (" + messageState(tlv.asByte()) + ")" : "");
                }
            }
            case Tlv.Tag.RECEIPTED_MESSAGE_ID, Tlv.Tag.ADDITIONAL_STATUS_INFO_TEXT -> {
                return quote(tlv.asCString());
            }
            default -> { }
        }
        return HEX.formatHex(tlv.value());
    }

    private static String messageState(int v) {
        return switch (v) {
            case 1 -> "ENROUTE";
            case 2 -> "DELIVERED";
            case 3 -> "EXPIRED";
            case 4 -> "DELETED";
            case 5 -> "UNDELIVERABLE";
            case 6 -> "ACCEPTED";
            case 7 -> "UNKNOWN";
            case 8 -> "REJECTED";
            default -> "reserved";
        };
    }

    private static String quote(String s) {
        return s.isEmpty() ? "(empty)" : "\"" + s + "\"";
    }

    private static final class Truncated extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Truncated(String field) {
            super(field, null, false, false);
        }
    }

    private interface Describer {
        String describe(int value);
    }

    private static final class Cursor {
        final byte[] pdu;
        final List<Field> fields = new ArrayList<>();
        int pos;

        Cursor(byte[] pdu) {
            this.pdu = pdu;
        }

        int remaining() {
            return pdu.length - pos;
        }

        int u8(String name, Describer describer) {
            need(1, name);
            int v = pdu[pos] & 0xFF;
            fields.add(new Field(pos, 1, name, describer.describe(v)));
            pos += 1;
            return v;
        }

        int u16(String name, Describer describer) {
            need(2, name);
            int v = ((pdu[pos] & 0xFF) << 8) | (pdu[pos + 1] & 0xFF);
            fields.add(new Field(pos, 2, name, describer.describe(v)));
            pos += 2;
            return v;
        }

        int u32(String name, Describer describer) {
            need(4, name);
            int v = ((pdu[pos] & 0xFF) << 24) | ((pdu[pos + 1] & 0xFF) << 16) | ((pdu[pos + 2] & 0xFF) << 8)
                    | (pdu[pos + 3] & 0xFF);
            fields.add(new Field(pos, 4, name, describer.describe(v)));
            pos += 4;
            return v;
        }

        String cstr(String name) {
            int end = pos;
            while (end < pdu.length && pdu[end] != 0) {
                end++;
            }
            if (end == pdu.length) {
                throw new Truncated(name + " (unterminated)");
            }
            String value = new String(pdu, pos, end - pos, StandardCharsets.ISO_8859_1);
            fields.add(new Field(pos, end - pos + 1, name, quote(value)));
            pos = end + 1;
            return value;
        }

        void bytes(String name, int length) {
            need(length, name);
            fields.add(new Field(pos, length, name, length == 0 ? "(empty)" : HEX.formatHex(pdu, pos, pos + length)));
            pos += length;
        }

        void rest(String name) {
            if (remaining() > 0) {
                bytes(name, remaining());
            }
        }

        void tlvs() {
            while (remaining() > 0) {
                int start = pos;
                need(4, "optional parameter header");
                int tag = ((pdu[pos] & 0xFF) << 8) | (pdu[pos + 1] & 0xFF);
                int length = ((pdu[pos + 2] & 0xFF) << 8) | (pdu[pos + 3] & 0xFF);
                pos += 4;
                need(length, Tlv.Tag.describe(tag) + " value");
                byte[] value = new byte[length];
                System.arraycopy(pdu, pos, value, 0, length);
                pos += length;
                Tlv tlv = new Tlv(tag, value);
                fields.add(new Field(start, pos - start, Tlv.Tag.describe(tag),
                        String.format("tag 0x%04X, %d byte%s: %s", tag, length, length == 1 ? "" : "s",
                                tlvValue(tlv))));
            }
        }

        private void need(int n, String name) {
            if (remaining() < n) {
                throw new Truncated(name);
            }
        }
    }

    static {
        assert Smpp.HEADER_LENGTH == 16;
    }
}
