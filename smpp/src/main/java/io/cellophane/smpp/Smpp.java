package io.cellophane.smpp;

/** Protocol-level constants for SMPP 3.4. */
public final class Smpp {

    /** {@code interface_version} value advertised in bind PDUs for SMPP 3.4. */
    public static final byte INTERFACE_VERSION_3_4 = 0x34;

    /** Length in bytes of the fixed PDU header: command_length, command_id, command_status, sequence_number. */
    public static final int HEADER_LENGTH = 16;

    /** Default SMSC listen port. */
    public static final int DEFAULT_PORT = 2775;

    private Smpp() {
    }
}
