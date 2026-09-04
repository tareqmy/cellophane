package io.cellophane.smpp.codec;

import io.cellophane.smpp.CommandId;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.pdu.GenericNack;
import io.netty.handler.codec.DecoderException;

/**
 * A PDU could not be decoded. Extends Netty's {@link DecoderException} so the pipeline propagates it unwrapped.
 * Carries the SMPP status the peer should be told, and the header fields of the
 * offending PDU when they could be read, so a {@code generic_nack} can be built with the right sequence number.
 */
public final class PduException extends DecoderException {

    private static final long serialVersionUID = 1L;

    private final int commandStatus;
    private final int commandId;
    private final int sequenceNumber;
    private final boolean headerKnown;

    public PduException(CommandStatus status, String message) {
        this(status.code(), message, null);
    }

    public PduException(int commandStatus, String message, Throwable cause) {
        super(message, cause);
        this.commandStatus = commandStatus;
        this.commandId = 0;
        this.sequenceNumber = 0;
        this.headerKnown = false;
    }

    private PduException(PduException source, int commandId, int sequenceNumber) {
        super(source.getMessage() + " in " + CommandId.describe(commandId) + " seq=" + sequenceNumber,
                source.getCause());
        this.commandStatus = source.commandStatus;
        this.commandId = commandId;
        this.sequenceNumber = sequenceNumber;
        this.headerKnown = true;
    }

    PduException withHeader(int commandId, int sequenceNumber) {
        return headerKnown ? this : new PduException(this, commandId, sequenceNumber);
    }

    public int commandStatus() {
        return commandStatus;
    }

    /** Command id of the PDU that failed, or 0 if the header itself could not be read. */
    public int commandId() {
        return commandId;
    }

    /** Sequence number of the PDU that failed, or 0 if the header itself could not be read. */
    public int sequenceNumber() {
        return sequenceNumber;
    }

    public boolean headerKnown() {
        return headerKnown;
    }

    /** The {@code generic_nack} an SMSC should send back for this failure. */
    public GenericNack toNack() {
        return new GenericNack(commandStatus, sequenceNumber);
    }
}
