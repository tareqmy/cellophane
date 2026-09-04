package io.cellophane.smpp.text;

import java.util.HashMap;
import java.util.Map;

/**
 * GSM 03.38 default 7-bit alphabet: text to septets and back, plus the 7-bit packing used on the air interface.
 * In SMPP, {@code data_coding} 0 usually carries <em>unpacked</em> septets (one per octet); packing is provided
 * for peers and tests that use the packed form.
 */
public final class Gsm7 {

    private static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    static final int ESCAPE = 0x1B;
    private static final int CR = 0x0D;

    private static final char[] BASIC_TABLE = BASIC.toCharArray();
    private static final char[] EXTENSION_TABLE = new char[128];
    private static final Map<Character, Integer> TO_BASIC = new HashMap<>();
    private static final Map<Character, Integer> TO_EXTENSION = new HashMap<>();

    static {
        if (BASIC_TABLE.length != 128) {
            throw new IllegalStateException("GSM basic table has " + BASIC_TABLE.length + " entries");
        }
        EXTENSION_TABLE[0x0A] = '\f';
        EXTENSION_TABLE[0x14] = '^';
        EXTENSION_TABLE[0x28] = '{';
        EXTENSION_TABLE[0x29] = '}';
        EXTENSION_TABLE[0x2F] = '\\';
        EXTENSION_TABLE[0x3C] = '[';
        EXTENSION_TABLE[0x3D] = '~';
        EXTENSION_TABLE[0x3E] = ']';
        EXTENSION_TABLE[0x40] = '|';
        EXTENSION_TABLE[0x65] = '€';
        for (int i = 0; i < 128; i++) {
            if (i != ESCAPE) {
                TO_BASIC.put(BASIC_TABLE[i], i);
            }
            if (EXTENSION_TABLE[i] != 0) {
                TO_EXTENSION.put(EXTENSION_TABLE[i], i);
            }
        }
    }

    private Gsm7() {
    }

    /** Whether every character of the text exists in the basic or extension table. */
    public static boolean canEncode(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!TO_BASIC.containsKey(c) && !TO_EXTENSION.containsKey(c)) {
                return false;
            }
        }
        return true;
    }

    /** Number of septets the text occupies; extension characters count twice. */
    public static int septetLength(CharSequence text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            n += TO_EXTENSION.containsKey(text.charAt(i)) ? 2 : 1;
        }
        return n;
    }

    /**
     * Encodes text as unpacked septets, one per byte.
     *
     * @throws IllegalArgumentException if a character is not representable
     */
    public static byte[] encode(CharSequence text) {
        byte[] out = new byte[septetLength(text)];
        int pos = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Integer basic = TO_BASIC.get(c);
            if (basic != null) {
                out[pos++] = basic.byteValue();
                continue;
            }
            Integer ext = TO_EXTENSION.get(c);
            if (ext == null) {
                throw new IllegalArgumentException(String.format("'%c' (U+%04X) is not in the GSM 7-bit alphabet",
                        c, (int) c));
            }
            out[pos++] = ESCAPE;
            out[pos++] = ext.byteValue();
        }
        return out;
    }

    /** Decodes unpacked septets. An escape followed by an undefined code yields the basic-table character. */
    public static String decode(byte[] septets) {
        StringBuilder sb = new StringBuilder(septets.length);
        for (int i = 0; i < septets.length; i++) {
            int s = septets[i] & 0x7F;
            if (s == ESCAPE && i + 1 < septets.length) {
                int next = septets[++i] & 0x7F;
                char ext = EXTENSION_TABLE[next];
                sb.append(ext != 0 ? ext : BASIC_TABLE[next]);
            } else if (s == ESCAPE) {
                sb.append(' ');
            } else {
                sb.append(BASIC_TABLE[s]);
            }
        }
        return sb.toString();
    }

    /** Packs septets into octets, 8 septets per 7 octets, least significant bits first. */
    public static byte[] pack(byte[] septets) {
        byte[] out = new byte[(septets.length * 7 + 7) / 8];
        int bit = 0;
        for (byte septet : septets) {
            int s = septet & 0x7F;
            int index = bit / 8;
            int shift = bit % 8;
            out[index] |= (byte) (s << shift);
            if (shift > 1) {
                out[index + 1] |= (byte) (s >> (8 - shift));
            }
            bit += 7;
        }
        return out;
    }

    /** Unpacks exactly {@code septetCount} septets from packed octets. */
    public static byte[] unpack(byte[] octets, int septetCount) {
        if (septetCount * 7 > octets.length * 8) {
            throw new IllegalArgumentException(septetCount + " septets do not fit in " + octets.length + " octets");
        }
        byte[] out = new byte[septetCount];
        for (int i = 0; i < septetCount; i++) {
            int bit = i * 7;
            int index = bit / 8;
            int shift = bit % 8;
            int v = (octets[index] & 0xFF) >> shift;
            if (shift > 1) {
                v |= (octets[index + 1] & 0xFF) << (8 - shift);
            }
            out[i] = (byte) (v & 0x7F);
        }
        return out;
    }

    /**
     * Unpacks as many septets as the octets hold. When the octet count leaves exactly seven padding bits
     * (a multiple of seven octets), a trailing CR or NUL is the padding septet defined by GSM 03.38 and is dropped.
     */
    public static byte[] unpack(byte[] octets) {
        int count = octets.length * 8 / 7;
        byte[] septets = unpack(octets, count);
        boolean sevenSpareBits = octets.length % 7 == 0;
        if (sevenSpareBits && count > 0 && (septets[count - 1] == CR || septets[count - 1] == 0)) {
            byte[] trimmed = new byte[count - 1];
            System.arraycopy(septets, 0, trimmed, 0, count - 1);
            return trimmed;
        }
        return septets;
    }
}
