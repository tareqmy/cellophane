package io.cellophane.smpp.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A User Data Header (3GPP 23.040 section 9.2.3.24): the information elements that prefix a short message. */
public record Udh(List<Element> elements) {

    /** Concatenated short message, 8-bit reference number. */
    public static final int IE_CONCAT_8 = 0x00;
    /** Application port addressing, 8-bit ports. */
    public static final int IE_PORT_8 = 0x04;
    /** Application port addressing, 16-bit ports. */
    public static final int IE_PORT_16 = 0x05;
    /** Concatenated short message, 16-bit reference number. */
    public static final int IE_CONCAT_16 = 0x08;

    public Udh {
        elements = List.copyOf(elements);
    }

    /** One information element. */
    public record Element(int id, byte[] data) {

        public Element {
            Objects.requireNonNull(data, "data");
            if (id < 0 || id > 0xFF || data.length > 0xFF) {
                throw new IllegalArgumentException("invalid information element");
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Element other && id == other.id && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return 31 * id + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return String.format("IE[0x%02X=%s]", id, HexFormat.of().formatHex(data));
        }
    }

    /** Concatenation info: which segment this is of how many, under which reference number. */
    public record Concat(int reference, int total, int sequence) {

        public Concat {
            if (total < 1 || total > 255 || sequence < 1 || sequence > total) {
                throw new IllegalArgumentException("invalid concat " + sequence + "/" + total);
            }
        }
    }

    /**
     * Parses the UDH at the start of the user data.
     *
     * @throws IllegalArgumentException if the declared lengths do not fit in the data
     */
    public static Udh parse(byte[] userData) {
        if (userData.length == 0) {
            throw new IllegalArgumentException("empty user data has no UDH");
        }
        int udhLength = userData[0] & 0xFF;
        if (udhLength > userData.length - 1) {
            throw new IllegalArgumentException("UDH length " + udhLength + " exceeds user data length "
                    + (userData.length - 1));
        }
        List<Element> elements = new ArrayList<>();
        int pos = 1;
        int end = 1 + udhLength;
        while (pos < end) {
            if (pos + 2 > end) {
                throw new IllegalArgumentException("truncated information element header at offset " + pos);
            }
            int id = userData[pos] & 0xFF;
            int length = userData[pos + 1] & 0xFF;
            if (pos + 2 + length > end) {
                throw new IllegalArgumentException("information element 0x" + Integer.toHexString(id)
                        + " overruns the UDH");
            }
            elements.add(new Element(id, Arrays.copyOfRange(userData, pos + 2, pos + 2 + length)));
            pos += 2 + length;
        }
        return new Udh(elements);
    }

    /** UDH for segment {@code sequence} of {@code total} with an 8-bit reference. */
    public static Udh concat8(int reference, int total, int sequence) {
        new Concat(reference & 0xFF, total, sequence);
        return new Udh(List.of(new Element(IE_CONCAT_8,
                new byte[] {(byte) reference, (byte) total, (byte) sequence})));
    }

    /** UDH for segment {@code sequence} of {@code total} with a 16-bit reference. */
    public static Udh concat16(int reference, int total, int sequence) {
        new Concat(reference & 0xFFFF, total, sequence);
        return new Udh(List.of(new Element(IE_CONCAT_16,
                new byte[] {(byte) (reference >> 8), (byte) reference, (byte) total, (byte) sequence})));
    }

    /** Total size in bytes including the leading length octet. */
    public int length() {
        int n = 1;
        for (Element e : elements) {
            n += 2 + e.data().length;
        }
        return n;
    }

    public byte[] toBytes() {
        byte[] out = new byte[length()];
        out[0] = (byte) (out.length - 1);
        int pos = 1;
        for (Element e : elements) {
            out[pos++] = (byte) e.id();
            out[pos++] = (byte) e.data().length;
            System.arraycopy(e.data(), 0, out, pos, e.data().length);
            pos += e.data().length;
        }
        return out;
    }

    public Optional<Element> element(int id) {
        for (Element e : elements) {
            if (e.id() == id) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Concatenation info from either the 8-bit or the 16-bit reference element, if present and well-formed. */
    public Optional<Concat> concat() {
        Optional<Element> ie8 = element(IE_CONCAT_8);
        if (ie8.isPresent() && ie8.get().data().length == 3) {
            byte[] d = ie8.get().data();
            return Optional.of(new Concat(d[0] & 0xFF, d[1] & 0xFF, d[2] & 0xFF));
        }
        Optional<Element> ie16 = element(IE_CONCAT_16);
        if (ie16.isPresent() && ie16.get().data().length == 4) {
            byte[] d = ie16.get().data();
            return Optional.of(new Concat(((d[0] & 0xFF) << 8) | (d[1] & 0xFF), d[2] & 0xFF, d[3] & 0xFF));
        }
        return Optional.empty();
    }

    /** The user data that follows this header. */
    public byte[] body(byte[] userData) {
        return Arrays.copyOfRange(userData, length(), userData.length);
    }

    /** Prepends this header to a message body. */
    public byte[] prepend(byte[] body) {
        byte[] header = toBytes();
        byte[] out = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }
}
