package io.cellophane.smpp.pdu;

import java.util.Objects;

/** An SMPP address triple: type of number, numbering plan indicator and the address digits/text. */
public record Address(int ton, int npi, String address) {

    public static final int TON_UNKNOWN = 0;
    public static final int TON_INTERNATIONAL = 1;
    public static final int TON_NATIONAL = 2;
    public static final int TON_NETWORK_SPECIFIC = 3;
    public static final int TON_SUBSCRIBER_NUMBER = 4;
    public static final int TON_ALPHANUMERIC = 5;
    public static final int TON_ABBREVIATED = 6;

    public static final int NPI_UNKNOWN = 0;
    public static final int NPI_ISDN = 1;
    public static final int NPI_DATA = 3;
    public static final int NPI_TELEX = 4;
    public static final int NPI_LAND_MOBILE = 6;
    public static final int NPI_NATIONAL = 8;
    public static final int NPI_PRIVATE = 9;
    public static final int NPI_ERMES = 10;
    public static final int NPI_INTERNET = 14;
    public static final int NPI_WAP_CLIENT_ID = 18;

    private static final Address EMPTY = new Address(TON_UNKNOWN, NPI_UNKNOWN, "");

    public Address {
        Objects.requireNonNull(address, "address");
    }

    public static Address empty() {
        return EMPTY;
    }

    /** International number in E.164 format without the leading plus. */
    public static Address international(String digits) {
        return new Address(TON_INTERNATIONAL, NPI_ISDN, digits);
    }

    /** Alphanumeric sender id such as a brand name. */
    public static Address alphanumeric(String text) {
        return new Address(TON_ALPHANUMERIC, NPI_UNKNOWN, text);
    }
}
