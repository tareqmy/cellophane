package io.cellophane.smpp.codec;

import static io.cellophane.smpp.CommandStatus.ESME_RINVCMDLEN;
import static io.cellophane.smpp.CommandStatus.ESME_RINVDSTADR;
import static io.cellophane.smpp.CommandStatus.ESME_RINVEXPIRY;
import static io.cellophane.smpp.CommandStatus.ESME_RINVMSGID;
import static io.cellophane.smpp.CommandStatus.ESME_RINVMSGLEN;
import static io.cellophane.smpp.CommandStatus.ESME_RINVOPTPARSTREAM;
import static io.cellophane.smpp.CommandStatus.ESME_RINVPARLEN;
import static io.cellophane.smpp.CommandStatus.ESME_RINVPASWD;
import static io.cellophane.smpp.CommandStatus.ESME_RINVSCHED;
import static io.cellophane.smpp.CommandStatus.ESME_RINVSERTYP;
import static io.cellophane.smpp.CommandStatus.ESME_RINVSRCADR;
import static io.cellophane.smpp.CommandStatus.ESME_RINVSYSID;
import static io.cellophane.smpp.CommandStatus.ESME_RINVSYSTYP;
import static io.cellophane.smpp.CommandStatus.ESME_ROK;
import static io.cellophane.smpp.Smpp.HEADER_LENGTH;

import io.cellophane.smpp.CommandId;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.Bind;
import io.cellophane.smpp.pdu.BindResp;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.DeliverSmResp;
import io.cellophane.smpp.pdu.EnquireLink;
import io.cellophane.smpp.pdu.EnquireLinkResp;
import io.cellophane.smpp.pdu.GenericNack;
import io.cellophane.smpp.pdu.MessagePdu;
import io.cellophane.smpp.pdu.Pdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.SubmitSmResp;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.pdu.Unbind;
import io.cellophane.smpp.pdu.UnbindResp;
import io.cellophane.smpp.pdu.UnknownPdu;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure (non-Netty-pipeline) SMPP 3.4 wire codec. {@link #decode} expects a buffer positioned at the start of a
 * complete PDU and consumes exactly {@code command_length} bytes; {@link #encode} appends one PDU to a buffer.
 */
public final class PduCodec {

    private PduCodec() {
    }

    public static Pdu decode(ByteBuf in) {
        if (in.readableBytes() < HEADER_LENGTH) {
            throw new PduException(ESME_RINVCMDLEN, "PDU shorter than the 16-byte header");
        }
        int commandLength = in.readInt();
        int commandId = in.readInt();
        int commandStatus = in.readInt();
        int sequenceNumber = in.readInt();
        if (commandLength < HEADER_LENGTH) {
            throw new PduException(ESME_RINVCMDLEN, "command_length " + commandLength + " is below the header size")
                    .withHeader(commandId, sequenceNumber);
        }
        int bodyLength = commandLength - HEADER_LENGTH;
        if (bodyLength > in.readableBytes()) {
            throw new PduException(ESME_RINVCMDLEN, "command_length " + commandLength + " exceeds the available "
                    + (in.readableBytes() + HEADER_LENGTH) + " bytes").withHeader(commandId, sequenceNumber);
        }
        ByteBuf body = in.readSlice(bodyLength);
        try {
            return decodeBody(commandId, commandStatus, sequenceNumber, body);
        } catch (PduException e) {
            throw e.withHeader(commandId, sequenceNumber);
        } catch (IndexOutOfBoundsException e) {
            throw new PduException(ESME_RINVMSGLEN.code(), "PDU body truncated", e)
                    .withHeader(commandId, sequenceNumber);
        }
    }

    private static Pdu decodeBody(int commandId, int status, int seq, ByteBuf body) {
        Optional<CommandId> known = CommandId.of(commandId);
        if (known.isEmpty()) {
            return new UnknownPdu(commandId, status, seq, Octets.readRemaining(body));
        }
        CommandId command = known.get();
        return switch (command) {
            case BIND_RECEIVER, BIND_TRANSMITTER, BIND_TRANSCEIVER -> decodeBind(command, seq, body);
            case BIND_RECEIVER_RESP, BIND_TRANSMITTER_RESP, BIND_TRANSCEIVER_RESP ->
                    decodeBindResp(command, status, seq, body);
            case UNBIND -> new Unbind(seq);
            case UNBIND_RESP -> new UnbindResp(status, seq);
            case ENQUIRE_LINK -> new EnquireLink(seq);
            case ENQUIRE_LINK_RESP -> new EnquireLinkResp(status, seq);
            case GENERIC_NACK -> new GenericNack(status, seq);
            case SUBMIT_SM -> decodeSubmitSm(seq, body);
            case SUBMIT_SM_RESP -> decodeSubmitSmResp(status, seq, body);
            case DELIVER_SM -> decodeDeliverSm(seq, body);
            case DELIVER_SM_RESP -> decodeDeliverSmResp(status, seq, body);
            default -> new UnknownPdu(commandId, status, seq, Octets.readRemaining(body));
        };
    }

    private static Bind decodeBind(CommandId command, int seq, ByteBuf body) {
        String systemId = Octets.readCString(body, ESME_RINVSYSID);
        String password = Octets.readCString(body, ESME_RINVPASWD);
        String systemType = Octets.readCString(body, ESME_RINVSYSTYP);
        int interfaceVersion = body.readUnsignedByte();
        Address addressRange = readAddress(body, ESME_RINVSRCADR);
        return new Bind(command, seq, systemId, password, systemType, interfaceVersion, addressRange);
    }

    private static BindResp decodeBindResp(CommandId command, int status, int seq, ByteBuf body) {
        String systemId = body.isReadable() ? Octets.readCString(body, ESME_RINVSYSID) : "";
        return new BindResp(command, status, seq, systemId, readTlvs(body));
    }

    private static SubmitSm decodeSubmitSm(int seq, ByteBuf body) {
        MessageBody m = readMessageBody(body);
        return new SubmitSm(seq, m.serviceType, m.source, m.destination, m.esmClass, m.protocolId, m.priorityFlag,
                m.scheduleDeliveryTime, m.validityPeriod, m.registeredDelivery, m.replaceIfPresent, m.dataCoding,
                m.smDefaultMsgId, m.shortMessage, m.tlvs);
    }

    private static DeliverSm decodeDeliverSm(int seq, ByteBuf body) {
        MessageBody m = readMessageBody(body);
        return new DeliverSm(seq, m.serviceType, m.source, m.destination, m.esmClass, m.protocolId, m.priorityFlag,
                m.scheduleDeliveryTime, m.validityPeriod, m.registeredDelivery, m.replaceIfPresent, m.dataCoding,
                m.smDefaultMsgId, m.shortMessage, m.tlvs);
    }

    private static SubmitSmResp decodeSubmitSmResp(int status, int seq, ByteBuf body) {
        String messageId = body.isReadable() ? Octets.readCString(body, ESME_RINVMSGID) : "";
        return new SubmitSmResp(status, seq, messageId, readTlvs(body));
    }

    private static DeliverSmResp decodeDeliverSmResp(int status, int seq, ByteBuf body) {
        if (body.isReadable()) {
            Octets.readCString(body, ESME_RINVMSGID); // always empty in 3.4; tolerated and ignored
        }
        readTlvs(body);
        return new DeliverSmResp(status, seq);
    }

    private record MessageBody(String serviceType, Address source, Address destination, int esmClass,
                               int protocolId, int priorityFlag, String scheduleDeliveryTime, String validityPeriod,
                               int registeredDelivery, int replaceIfPresent, int dataCoding, int smDefaultMsgId,
                               byte[] shortMessage, List<Tlv> tlvs) {
    }

    private static MessageBody readMessageBody(ByteBuf body) {
        String serviceType = Octets.readCString(body, ESME_RINVSERTYP);
        Address source = readAddress(body, ESME_RINVSRCADR);
        Address destination = readAddress(body, ESME_RINVDSTADR);
        int esmClass = body.readUnsignedByte();
        int protocolId = body.readUnsignedByte();
        int priorityFlag = body.readUnsignedByte();
        String scheduleDeliveryTime = Octets.readCString(body, ESME_RINVSCHED);
        String validityPeriod = Octets.readCString(body, ESME_RINVEXPIRY);
        int registeredDelivery = body.readUnsignedByte();
        int replaceIfPresent = body.readUnsignedByte();
        int dataCoding = body.readUnsignedByte();
        int smDefaultMsgId = body.readUnsignedByte();
        int smLength = body.readUnsignedByte();
        if (smLength > body.readableBytes()) {
            throw new PduException(ESME_RINVMSGLEN, "sm_length " + smLength + " exceeds remaining "
                    + body.readableBytes() + " bytes");
        }
        byte[] shortMessage = Octets.readBytes(body, smLength);
        return new MessageBody(serviceType, source, destination, esmClass, protocolId, priorityFlag,
                scheduleDeliveryTime, validityPeriod, registeredDelivery, replaceIfPresent, dataCoding,
                smDefaultMsgId, shortMessage, readTlvs(body));
    }

    private static Address readAddress(ByteBuf body, io.cellophane.smpp.CommandStatus error) {
        int ton = body.readUnsignedByte();
        int npi = body.readUnsignedByte();
        return new Address(ton, npi, Octets.readCString(body, error));
    }

    private static List<Tlv> readTlvs(ByteBuf body) {
        if (!body.isReadable()) {
            return List.of();
        }
        List<Tlv> tlvs = new ArrayList<>();
        while (body.isReadable()) {
            if (body.readableBytes() < 4) {
                throw new PduException(ESME_RINVOPTPARSTREAM, "trailing " + body.readableBytes()
                        + " bytes are too short for a TLV header");
            }
            int tag = body.readUnsignedShort();
            int length = body.readUnsignedShort();
            if (length > body.readableBytes()) {
                throw new PduException(ESME_RINVPARLEN, "TLV " + Tlv.Tag.describe(tag) + " declares " + length
                        + " bytes but only " + body.readableBytes() + " remain");
            }
            tlvs.add(new Tlv(tag, Octets.readBytes(body, length)));
        }
        return tlvs;
    }

    // ---------------------------------------------------------------- encoding

    /** Encodes a PDU into a fresh byte array. */
    public static byte[] encode(Pdu pdu) {
        ByteBuf buf = Unpooled.buffer(64);
        try {
            encode(pdu, buf);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    public static void encode(Pdu pdu, ByteBuf out) {
        int start = out.writerIndex();
        out.writeInt(0);
        out.writeInt(pdu.commandId());
        out.writeInt(pdu.commandStatus());
        out.writeInt(pdu.sequenceNumber());
        switch (pdu) {
            case Bind b -> {
                Octets.writeCString(out, b.systemId());
                Octets.writeCString(out, b.password());
                Octets.writeCString(out, b.systemType());
                out.writeByte(b.interfaceVersion());
                writeAddress(out, b.addressRange());
            }
            case BindResp r -> {
                if (r.commandStatus() == ESME_ROK.code()) {
                    Octets.writeCString(out, r.systemId());
                    writeTlvs(out, r.tlvs());
                }
            }
            case SubmitSm s -> writeMessageBody(out, s);
            case DeliverSm d -> writeMessageBody(out, d);
            case SubmitSmResp r -> {
                if (r.commandStatus() == ESME_ROK.code()) {
                    Octets.writeCString(out, r.messageId());
                    writeTlvs(out, r.tlvs());
                }
            }
            case DeliverSmResp r -> Octets.writeCString(out, "");
            case UnknownPdu u -> out.writeBytes(u.body());
            case Unbind u -> { }
            case UnbindResp u -> { }
            case EnquireLink e -> { }
            case EnquireLinkResp e -> { }
            case GenericNack g -> { }
        }
        out.setInt(start, out.writerIndex() - start);
    }

    private static void writeMessageBody(ByteBuf out, MessagePdu m) {
        Octets.writeCString(out, m.serviceType());
        writeAddress(out, m.source());
        writeAddress(out, m.destination());
        out.writeByte(m.esmClass());
        out.writeByte(m.protocolId());
        out.writeByte(m.priorityFlag());
        Octets.writeCString(out, m.scheduleDeliveryTime());
        Octets.writeCString(out, m.validityPeriod());
        out.writeByte(m.registeredDelivery());
        out.writeByte(m.replaceIfPresent());
        out.writeByte(m.dataCoding());
        out.writeByte(m.smDefaultMsgId());
        out.writeByte(m.shortMessage().length);
        out.writeBytes(m.shortMessage());
        writeTlvs(out, m.tlvs());
    }

    private static void writeAddress(ByteBuf out, Address address) {
        out.writeByte(address.ton());
        out.writeByte(address.npi());
        Octets.writeCString(out, address.address());
    }

    private static void writeTlvs(ByteBuf out, List<Tlv> tlvs) {
        for (Tlv tlv : tlvs) {
            out.writeShort(tlv.tag());
            out.writeShort(tlv.length());
            out.writeBytes(tlv.value());
        }
    }
}
