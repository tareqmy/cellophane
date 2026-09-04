package io.cellophane.smpp.pdu;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** A well-formed PDU whose command id the codec does not decode. The body is kept verbatim. */
public record UnknownPdu(int commandId, int commandStatus, int sequenceNumber, byte[] body) implements Pdu {

    public UnknownPdu {
        Objects.requireNonNull(body, "body");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UnknownPdu other && commandId == other.commandId && commandStatus == other.commandStatus
                && sequenceNumber == other.sequenceNumber && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandId, commandStatus, sequenceNumber, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return String.format("UnknownPdu[commandId=0x%08X, status=%d, seq=%d, body=%s]", commandId, commandStatus,
                sequenceNumber, HexFormat.of().formatHex(body));
    }
}
