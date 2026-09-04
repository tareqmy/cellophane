package io.cellophane.smpp.pdu;

import io.cellophane.smpp.CommandId;

import java.util.Objects;

/** {@code bind_transmitter}, {@code bind_receiver} or {@code bind_transceiver}; the three share one body layout. */
public record Bind(CommandId command, int sequenceNumber, String systemId, String password, String systemType,
                   int interfaceVersion, Address addressRange) implements Pdu {

    public Bind {
        Objects.requireNonNull(command, "command");
        if (!command.isBind()) {
            throw new IllegalArgumentException("not a bind command: " + command);
        }
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(systemType, "systemType");
        Objects.requireNonNull(addressRange, "addressRange");
    }

    public static Bind transceiver(int sequenceNumber, String systemId, String password) {
        return new Bind(CommandId.BIND_TRANSCEIVER, sequenceNumber, systemId, password, "", 0x34, Address.empty());
    }

    public static Bind transmitter(int sequenceNumber, String systemId, String password) {
        return new Bind(CommandId.BIND_TRANSMITTER, sequenceNumber, systemId, password, "", 0x34, Address.empty());
    }

    public static Bind receiver(int sequenceNumber, String systemId, String password) {
        return new Bind(CommandId.BIND_RECEIVER, sequenceNumber, systemId, password, "", 0x34, Address.empty());
    }

    @Override
    public int commandId() {
        return command.id();
    }

    @Override
    public int commandStatus() {
        return 0;
    }

    public boolean canTransmit() {
        return command != CommandId.BIND_RECEIVER;
    }

    public boolean canReceive() {
        return command != CommandId.BIND_TRANSMITTER;
    }

    /** The matching response with the given status. */
    public BindResp respond(int commandStatus, String smscSystemId) {
        return new BindResp(command.response().orElseThrow(), commandStatus, sequenceNumber, smscSystemId,
                java.util.List.of());
    }
}
