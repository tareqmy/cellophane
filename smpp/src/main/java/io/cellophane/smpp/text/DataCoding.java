package io.cellophane.smpp.text;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Interpretation of the SMPP {@code data_coding} octet (spec section 5.2.19 and 3GPP 23.038 section 4). */
public final class DataCoding {

    public static final int SMSC_DEFAULT = 0x00;
    public static final int IA5 = 0x01;
    public static final int OCTET_UNSPECIFIED_2 = 0x02;
    public static final int LATIN_1 = 0x03;
    public static final int OCTET_UNSPECIFIED_4 = 0x04;
    public static final int JIS = 0x05;
    public static final int CYRILLIC = 0x06;
    public static final int LATIN_HEBREW = 0x07;
    public static final int UCS2 = 0x08;
    public static final int PICTOGRAM = 0x09;
    public static final int ISO_2022_JP = 0x0A;
    public static final int EXTENDED_KANJI = 0x0D;
    public static final int KS_C_5601 = 0x0E;

    /** How the user data bytes should be turned into text. */
    public enum Alphabet {
        GSM7("GSM 7-bit", null),
        ASCII("ASCII", StandardCharsets.US_ASCII),
        LATIN1("Latin-1", StandardCharsets.ISO_8859_1),
        CYRILLIC("Cyrillic (ISO-8859-5)", charset("ISO-8859-5")),
        HEBREW("Hebrew (ISO-8859-8)", charset("ISO-8859-8")),
        UCS2("UCS-2", StandardCharsets.UTF_16BE),
        BINARY("binary", null),
        UNKNOWN("unknown", null);

        private final String label;
        private final Charset charset;

        Alphabet(String label, Charset charset) {
            this.label = label;
            this.charset = charset;
        }

        public String label() {
            return label;
        }

        /** The Java charset for the text alphabets other than GSM 7-bit. */
        public Optional<Charset> charset() {
            return Optional.ofNullable(charset);
        }

        public boolean isText() {
            return this != BINARY && this != UNKNOWN;
        }

        private static Charset charset(String name) {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        }
    }

    private DataCoding() {
    }

    /** Resolves the alphabet, treating the SMSC default as GSM 7-bit as Cellophane's fake operator does. */
    public static Alphabet alphabet(int dataCoding) {
        int dc = dataCoding & 0xFF;
        if ((dc & 0xF0) == 0xF0) {
            // Data coding / message class group: bit 2 selects 8-bit data.
            return (dc & 0x04) != 0 ? Alphabet.BINARY : Alphabet.GSM7;
        }
        if ((dc & 0xC0) == 0xC0) {
            // Message waiting indication groups: 0xC0/0xD0 discard/store with GSM 7-bit, 0xE0 with UCS-2.
            return (dc & 0xF0) == 0xE0 ? Alphabet.UCS2 : Alphabet.GSM7;
        }
        return switch (dc) {
            case SMSC_DEFAULT -> Alphabet.GSM7;
            case IA5 -> Alphabet.ASCII;
            case LATIN_1 -> Alphabet.LATIN1;
            case CYRILLIC -> Alphabet.CYRILLIC;
            case LATIN_HEBREW -> Alphabet.HEBREW;
            case UCS2 -> Alphabet.UCS2;
            case OCTET_UNSPECIFIED_2, OCTET_UNSPECIFIED_4 -> Alphabet.BINARY;
            default -> Alphabet.UNKNOWN;
        };
    }

    /** Message class (0-3) when the data_coding carries one, e.g. class 0 "flash" messages. */
    public static Optional<Integer> messageClass(int dataCoding) {
        int dc = dataCoding & 0xFF;
        boolean hasClass = (dc & 0xF0) == 0xF0 || ((dc & 0xC0) == 0 && (dc & 0x10) != 0);
        return hasClass ? Optional.of(dc & 0x03) : Optional.empty();
    }
}
