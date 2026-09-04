package io.cellophane.smpp.codec;

import io.cellophane.smpp.CommandStatus;
import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Reading and writing of the SMPP primitive field types. */
final class Octets {

    /** SMPP C-octet strings are ASCII; Latin-1 keeps every byte value reversible. */
    static final Charset CHARSET = StandardCharsets.ISO_8859_1;

    private Octets() {
    }

    /**
     * Reads a NUL-terminated string. Length limits from the spec are deliberately not enforced: real operators
     * accept over-long fields (the README's own default password is ten characters) and a test double should too.
     *
     * @param error status to report if the string is missing its terminator
     */
    static String readCString(ByteBuf in, CommandStatus error) {
        int length = in.bytesBefore((byte) 0);
        if (length < 0) {
            throw new PduException(error, "unterminated C-octet string");
        }
        String value = in.toString(in.readerIndex(), length, CHARSET);
        in.skipBytes(length + 1);
        return value;
    }

    static void writeCString(ByteBuf out, String value) {
        out.writeCharSequence(value, CHARSET);
        out.writeByte(0);
    }

    static byte[] readBytes(ByteBuf in, int length) {
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return bytes;
    }

    static byte[] readRemaining(ByteBuf in) {
        return readBytes(in, in.readableBytes());
    }
}
