package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

import java.util.Optional;

/**
 * A decoded SMPP 3.4 protocol data unit. Every PDU carries the four header fields; the body depends on the type.
 * Requests always report {@code command_status} 0.
 */
public sealed interface Pdu
        permits Bind, BindResp, Unbind, UnbindResp, EnquireLink, EnquireLinkResp, GenericNack,
                SubmitSm, SubmitSmResp, DeliverSm, DeliverSmResp, UnknownPdu {

    int commandId();

    int commandStatus();

    int sequenceNumber();

    default boolean isResponse() {
        return CommandId.isResponse(commandId());
    }

    /** The known command, if this PDU's id is one the codec understands. */
    default Optional<CommandId> knownCommand() {
        return CommandId.of(commandId());
    }
}
