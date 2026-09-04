package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

import java.util.List;
import java.util.Objects;

/** Response to any of the three bind requests. The body (system_id, TLVs) is only sent when the status is 0. */
public record BindResp(CommandId command, int commandStatus, int sequenceNumber, String systemId, List<Tlv> tlvs)
        implements Pdu {

    public BindResp {
        Objects.requireNonNull(command, "command");
        boolean bindResponse = command.isResponse()
                && CommandId.of(command.id() & ~CommandId.RESPONSE_BIT).map(CommandId::isBind).orElse(false);
        if (!bindResponse) {
            throw new IllegalArgumentException("not a bind response: " + command);
        }
        Objects.requireNonNull(systemId, "systemId");
        tlvs = List.copyOf(tlvs);
    }

    @Override
    public int commandId() {
        return command.id();
    }
}
