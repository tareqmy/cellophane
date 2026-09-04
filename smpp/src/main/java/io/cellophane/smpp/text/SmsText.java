package io.cellophane.smpp.text;

import java.nio.charset.Charset;
import java.util.Optional;

/** Converts user data to and from text according to the {@code data_coding} alphabet. */
public final class SmsText {

    private SmsText() {
    }

    /**
     * Decodes user data (without any UDH) to text. Empty for binary or unknown alphabets.
     *
     * @param packedGsm7 whether GSM 7-bit data is packed 8 septets to 7 octets rather than one septet per octet
     */
    public static Optional<String> decode(int dataCoding, byte[] data, boolean packedGsm7) {
        DataCoding.Alphabet alphabet = DataCoding.alphabet(dataCoding);
        return switch (alphabet) {
            case GSM7 -> Optional.of(Gsm7.decode(packedGsm7 ? Gsm7.unpack(data) : data));
            case BINARY, UNKNOWN -> Optional.empty();
            default -> alphabet.charset().map(cs -> new String(data, cs));
        };
    }

    /** Decodes assuming unpacked GSM 7-bit, the usual SMPP convention. */
    public static Optional<String> decode(int dataCoding, byte[] data) {
        return decode(dataCoding, data, false);
    }

    /**
     * Encodes text for the given data_coding (unpacked for GSM 7-bit).
     *
     * @throws IllegalArgumentException if the alphabet cannot carry text or the text is not representable
     */
    public static byte[] encode(int dataCoding, String text) {
        DataCoding.Alphabet alphabet = DataCoding.alphabet(dataCoding);
        return switch (alphabet) {
            case GSM7 -> Gsm7.encode(text);
            case BINARY, UNKNOWN -> throw new IllegalArgumentException("data_coding 0x"
                    + Integer.toHexString(dataCoding) + " does not carry text");
            default -> {
                Charset cs = alphabet.charset().orElseThrow(() -> new IllegalArgumentException(
                        alphabet.label() + " is not supported by this JVM"));
                if (!cs.newEncoder().canEncode(text)) {
                    throw new IllegalArgumentException("text is not representable in " + alphabet.label());
                }
                yield text.getBytes(cs);
            }
        };
    }

    /** Picks the smallest alphabet that can carry the text: GSM 7-bit, else UCS-2. */
    public static int chooseDataCoding(String text) {
        return Gsm7.canEncode(text) ? DataCoding.SMSC_DEFAULT : DataCoding.UCS2;
    }
}
