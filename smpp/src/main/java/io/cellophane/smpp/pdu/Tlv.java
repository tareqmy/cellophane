package io.cellophane.smpp.pdu;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** An SMPP optional parameter (tag-length-value). Values are treated as immutable; callers must not mutate them. */
public record Tlv(int tag, byte[] value) {

    public Tlv {
        Objects.requireNonNull(value, "value");
        if (tag < 0 || tag > 0xFFFF) {
            throw new IllegalArgumentException("tag out of range: " + tag);
        }
        if (value.length > 0xFFFF) {
            throw new IllegalArgumentException("value longer than 65535 bytes");
        }
    }

    public static Tlv ofByte(int tag, int value) {
        return new Tlv(tag, new byte[] {(byte) value});
    }

    public static Tlv ofShort(int tag, int value) {
        return new Tlv(tag, new byte[] {(byte) (value >>> 8), (byte) value});
    }

    public static Tlv ofInt(int tag, int value) {
        return new Tlv(tag, new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    /** A null-terminated string value (C-octet string). */
    public static Tlv ofCString(int tag, String value) {
        byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = Arrays.copyOf(text, text.length + 1);
        return new Tlv(tag, bytes);
    }

    public int length() {
        return value.length;
    }

    public int asByte() {
        require(1);
        return value[0] & 0xFF;
    }

    public int asShort() {
        require(2);
        return ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
    }

    public int asInt() {
        require(4);
        return ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16) | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
    }

    /** The value as a string, stopping at the first NUL if present. */
    public String asCString() {
        int end = 0;
        while (end < value.length && value[end] != 0) {
            end++;
        }
        return new String(value, 0, end, StandardCharsets.ISO_8859_1);
    }

    public String tagName() {
        return Tag.describe(tag);
    }

    private void require(int length) {
        if (value.length != length) {
            throw new IllegalStateException(tagName() + " has length " + value.length + ", expected " + length);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Tlv other && tag == other.tag && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * tag + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Tlv[" + tagName() + "=" + HexFormat.of().formatHex(value) + "]";
    }

    /** Well-known SMPP 3.4 optional parameter tags (spec section 5.3.2). */
    public static final class Tag {
        public static final int DEST_ADDR_SUBUNIT = 0x0005;
        public static final int DEST_NETWORK_TYPE = 0x0006;
        public static final int DEST_BEARER_TYPE = 0x0007;
        public static final int DEST_TELEMATICS_ID = 0x0008;
        public static final int SOURCE_ADDR_SUBUNIT = 0x000D;
        public static final int SOURCE_NETWORK_TYPE = 0x000E;
        public static final int SOURCE_BEARER_TYPE = 0x000F;
        public static final int SOURCE_TELEMATICS_ID = 0x0010;
        public static final int QOS_TIME_TO_LIVE = 0x0017;
        public static final int PAYLOAD_TYPE = 0x0019;
        public static final int ADDITIONAL_STATUS_INFO_TEXT = 0x001D;
        public static final int RECEIPTED_MESSAGE_ID = 0x001E;
        public static final int MS_MSG_WAIT_FACILITIES = 0x0030;
        public static final int PRIVACY_INDICATOR = 0x0201;
        public static final int SOURCE_SUBADDRESS = 0x0202;
        public static final int DEST_SUBADDRESS = 0x0203;
        public static final int USER_MESSAGE_REFERENCE = 0x0204;
        public static final int USER_RESPONSE_CODE = 0x0205;
        public static final int SOURCE_PORT = 0x020A;
        public static final int DESTINATION_PORT = 0x020B;
        public static final int SAR_MSG_REF_NUM = 0x020C;
        public static final int LANGUAGE_INDICATOR = 0x020D;
        public static final int SAR_TOTAL_SEGMENTS = 0x020E;
        public static final int SAR_SEGMENT_SEQNUM = 0x020F;
        public static final int SC_INTERFACE_VERSION = 0x0210;
        public static final int CALLBACK_NUM_PRES_IND = 0x0302;
        public static final int CALLBACK_NUM_ATAG = 0x0303;
        public static final int NUMBER_OF_MESSAGES = 0x0304;
        public static final int CALLBACK_NUM = 0x0381;
        public static final int DPF_RESULT = 0x0420;
        public static final int SET_DPF = 0x0421;
        public static final int MS_AVAILABILITY_STATUS = 0x0422;
        public static final int NETWORK_ERROR_CODE = 0x0423;
        public static final int MESSAGE_PAYLOAD = 0x0424;
        public static final int DELIVERY_FAILURE_REASON = 0x0425;
        public static final int MORE_MESSAGES_TO_SEND = 0x0426;
        public static final int MESSAGE_STATE = 0x0427;
        public static final int USSD_SERVICE_OP = 0x0501;
        public static final int DISPLAY_TIME = 0x1201;
        public static final int SMS_SIGNAL = 0x1203;
        public static final int MS_VALIDITY = 0x1204;
        public static final int ALERT_ON_MESSAGE_DELIVERY = 0x130C;
        public static final int ITS_REPLY_TYPE = 0x1380;
        public static final int ITS_SESSION_INFO = 0x1383;

        private Tag() {
        }

        public static String describe(int tag) {
            return switch (tag) {
                case DEST_ADDR_SUBUNIT -> "dest_addr_subunit";
                case DEST_NETWORK_TYPE -> "dest_network_type";
                case DEST_BEARER_TYPE -> "dest_bearer_type";
                case DEST_TELEMATICS_ID -> "dest_telematics_id";
                case SOURCE_ADDR_SUBUNIT -> "source_addr_subunit";
                case SOURCE_NETWORK_TYPE -> "source_network_type";
                case SOURCE_BEARER_TYPE -> "source_bearer_type";
                case SOURCE_TELEMATICS_ID -> "source_telematics_id";
                case QOS_TIME_TO_LIVE -> "qos_time_to_live";
                case PAYLOAD_TYPE -> "payload_type";
                case ADDITIONAL_STATUS_INFO_TEXT -> "additional_status_info_text";
                case RECEIPTED_MESSAGE_ID -> "receipted_message_id";
                case MS_MSG_WAIT_FACILITIES -> "ms_msg_wait_facilities";
                case PRIVACY_INDICATOR -> "privacy_indicator";
                case SOURCE_SUBADDRESS -> "source_subaddress";
                case DEST_SUBADDRESS -> "dest_subaddress";
                case USER_MESSAGE_REFERENCE -> "user_message_reference";
                case USER_RESPONSE_CODE -> "user_response_code";
                case SOURCE_PORT -> "source_port";
                case DESTINATION_PORT -> "destination_port";
                case SAR_MSG_REF_NUM -> "sar_msg_ref_num";
                case LANGUAGE_INDICATOR -> "language_indicator";
                case SAR_TOTAL_SEGMENTS -> "sar_total_segments";
                case SAR_SEGMENT_SEQNUM -> "sar_segment_seqnum";
                case SC_INTERFACE_VERSION -> "sc_interface_version";
                case CALLBACK_NUM_PRES_IND -> "callback_num_pres_ind";
                case CALLBACK_NUM_ATAG -> "callback_num_atag";
                case NUMBER_OF_MESSAGES -> "number_of_messages";
                case CALLBACK_NUM -> "callback_num";
                case DPF_RESULT -> "dpf_result";
                case SET_DPF -> "set_dpf";
                case MS_AVAILABILITY_STATUS -> "ms_availability_status";
                case NETWORK_ERROR_CODE -> "network_error_code";
                case MESSAGE_PAYLOAD -> "message_payload";
                case DELIVERY_FAILURE_REASON -> "delivery_failure_reason";
                case MORE_MESSAGES_TO_SEND -> "more_messages_to_send";
                case MESSAGE_STATE -> "message_state";
                case USSD_SERVICE_OP -> "ussd_service_op";
                case DISPLAY_TIME -> "display_time";
                case SMS_SIGNAL -> "sms_signal";
                case MS_VALIDITY -> "ms_validity";
                case ALERT_ON_MESSAGE_DELIVERY -> "alert_on_message_delivery";
                case ITS_REPLY_TYPE -> "its_reply_type";
                case ITS_SESSION_INFO -> "its_session_info";
                default -> String.format("0x%04X", tag);
            };
        }
    }
}
