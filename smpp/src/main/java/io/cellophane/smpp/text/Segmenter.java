package io.cellophane.smpp.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into the parts a network would carry: one short message when it fits, otherwise concatenated parts
 * with an 8-bit reference UDH. GSM 7-bit fits 160 septets alone or 153 per part; UCS-2 fits 70 characters alone or
 * 67 per part. Escape pairs and surrogate pairs are never split.
 */
public final class Segmenter {

    public static final int GSM7_SINGLE = 160;
    public static final int GSM7_PART = 153;
    public static final int UCS2_SINGLE = 70;
    public static final int UCS2_PART = 67;

    /** One part's user data (UDH included when concatenated) and whether a UDH is present. */
    public record Part(byte[] userData, boolean hasUdh) {
    }

    private Segmenter() {
    }

    /**
     * @param dataCoding 0 for GSM 7-bit (unpacked septets) or 8 for UCS-2; other text codings are treated as UCS-2
     * @param reference  the concat reference to use if the text needs splitting
     */
    public static List<Part> split(String text, int dataCoding, int reference) {
        boolean gsm = DataCoding.alphabet(dataCoding) == DataCoding.Alphabet.GSM7;
        List<byte[]> bodies = gsm ? splitGsm7(text) : splitUcs2(text);
        if (bodies.size() == 1) {
            return List.of(new Part(bodies.getFirst(), false));
        }
        List<Part> parts = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            parts.add(new Part(Udh.concat8(reference, bodies.size(), i + 1).prepend(bodies.get(i)), true));
        }
        return parts;
    }

    private static List<byte[]> splitGsm7(String text) {
        byte[] septets = Gsm7.encode(text);
        if (septets.length <= GSM7_SINGLE) {
            return List.of(septets);
        }
        List<byte[]> out = new ArrayList<>();
        int pos = 0;
        while (pos < septets.length) {
            int end = Math.min(pos + GSM7_PART, septets.length);
            if (end < septets.length && septets[end - 1] == Gsm7.ESCAPE) {
                end--; // keep the escape with its extension character
            }
            byte[] part = new byte[end - pos];
            System.arraycopy(septets, pos, part, 0, part.length);
            out.add(part);
            pos = end;
        }
        return out;
    }

    private static List<byte[]> splitUcs2(String text) {
        if (text.length() <= UCS2_SINGLE) {
            return List.of(SmsText.encode(DataCoding.UCS2, text));
        }
        List<byte[]> out = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + UCS2_PART, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            out.add(SmsText.encode(DataCoding.UCS2, text.substring(pos, end)));
            pos = end;
        }
        return out;
    }
}
