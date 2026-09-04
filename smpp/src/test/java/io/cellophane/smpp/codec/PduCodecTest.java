package io.cellophane.smpp.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cellophane.smpp.CommandId;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.Bind;
import io.cellophane.smpp.pdu.BindResp;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.DeliverSmResp;
import io.cellophane.smpp.pdu.EnquireLink;
import io.cellophane.smpp.pdu.EnquireLinkResp;
import io.cellophane.smpp.pdu.GenericNack;
import io.cellophane.smpp.pdu.Pdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.SubmitSmResp;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.pdu.Unbind;
import io.cellophane.smpp.pdu.UnbindResp;
import io.cellophane.smpp.pdu.UnknownPdu;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PduCodecTest {

    private static final HexFormat HEX = HexFormat.of();

    static Stream<Pdu> pdus() {
        return Stream.of(
                Bind.transceiver(1, "smppclient1", "password"),
                new Bind(CommandId.BIND_RECEIVER, 2, "rx", "pw", "SMPP", 0x34, new Address(1, 1, "8801")),
                new BindResp(CommandId.BIND_TRANSCEIVER_RESP, 0, 1, "cellophane",
                        List.of(Tlv.ofByte(Tlv.Tag.SC_INTERFACE_VERSION, 0x34))),
                new BindResp(CommandId.BIND_TRANSMITTER_RESP, CommandStatus.ESME_RINVPASWD.code(), 7, "", List.of()),
                new Unbind(3), new UnbindResp(0, 3),
                new EnquireLink(4), new EnquireLinkResp(0, 4),
                new GenericNack(CommandStatus.ESME_RINVCMDID.code(), 0),
                SubmitSm.of(5, Address.alphanumeric("MyApp"), Address.international("8801711111111"), 0,
                        "Your OTP is 482913".getBytes(StandardCharsets.US_ASCII)),
                new SubmitSm(6, "OTP", Address.alphanumeric("Bank"), Address.international("15551234567"), 0x40, 0, 1,
                        "", "000000000100000R", 1, 0, 8, 0, " H i".getBytes(StandardCharsets.ISO_8859_1),
                        List.of(Tlv.ofShort(Tlv.Tag.SAR_MSG_REF_NUM, 0x1234),
                                Tlv.ofByte(Tlv.Tag.SAR_TOTAL_SEGMENTS, 2), Tlv.ofByte(Tlv.Tag.SAR_SEGMENT_SEQNUM, 1))),
                new SubmitSm(7, "", Address.empty(), Address.international("1"), 0, 0, 0, "", "", 0, 0, 4, 0,
                        new byte[0], List.of(new Tlv(Tlv.Tag.MESSAGE_PAYLOAD, new byte[300]))),
                new SubmitSmResp(0, 5, "d0c0ffee", List.of()),
                new SubmitSmResp(CommandStatus.ESME_RTHROTTLED.code(), 6, "", List.of()),
                DeliverSm.of(8, Address.international("8801711111111"), Address.alphanumeric("MyApp"), 0,
                        "STOP".getBytes(StandardCharsets.US_ASCII)),
                DeliverSm.receipt(9, Address.international("8801711111111"), Address.alphanumeric("MyApp"),
                        "id:42 sub:001 dlvrd:001 stat:DELIVRD".getBytes(StandardCharsets.US_ASCII),
                        List.of(Tlv.ofCString(Tlv.Tag.RECEIPTED_MESSAGE_ID, "42"),
                                Tlv.ofByte(Tlv.Tag.MESSAGE_STATE, 2))),
                new DeliverSmResp(0, 8),
                new UnknownPdu(0x00000103, 0, 10, new byte[] {1, 2, 3}));
    }

    @ParameterizedTest
    @MethodSource("pdus")
    void roundTripsThroughTheWireFormat(Pdu pdu) {
        byte[] wire = PduCodec.encode(pdu);
        ByteBuf buf = Unpooled.wrappedBuffer(wire);

        Pdu decoded = PduCodec.decode(buf);

        assertThat(decoded).isEqualTo(pdu);
        assertThat(buf.isReadable()).as("decode consumes exactly one PDU").isFalse();
        assertThat(wire.length).isEqualTo(Unpooled.wrappedBuffer(wire).readInt());
    }

    @Test
    void encodesBindTransceiverExactlyAsTheSpecLaysItOut() {
        byte[] wire = PduCodec.encode(Bind.transceiver(1, "smppclient1", "password"));

        assertThat(HEX.formatHex(wire)).isEqualTo(
                "0000002a" + "00000009" + "00000000" + "00000001"
                + HEX.formatHex("smppclient1".getBytes(StandardCharsets.US_ASCII)) + "00"
                + HEX.formatHex("password".getBytes(StandardCharsets.US_ASCII)) + "00"
                + "00" + "34" + "00" + "00" + "00");
    }

    @Test
    void encodesSubmitSmFieldsInOrder() {
        SubmitSm pdu = new SubmitSm(2, "", new Address(5, 0, "A"), new Address(1, 1, "1"), 0, 0, 0, "", "", 1, 0, 8,
                0, new byte[] {0x00, 0x48}, List.of());

        assertThat(HEX.formatHex(PduCodec.encode(pdu))).isEqualTo(
                "00000025" + "00000004" + "00000000" + "00000002"
                + "00" + "0500" + "4100" + "0101" + "3100" + "00" + "00" + "00" + "00" + "00"
                + "01" + "00" + "08" + "00" + "02" + "0048");
    }

    @Test
    void decodesUnknownCommandsVerbatim() {
        Pdu pdu = PduCodec.decode(Unpooled.wrappedBuffer(HEX.parseHex("00000013" + "00000021" + "00000000"
                + "00000009" + "aabbcc")));

        assertThat(pdu).isEqualTo(new UnknownPdu(CommandId.SUBMIT_MULTI.id(), 0, 9, new byte[] {
                (byte) 0xaa, (byte) 0xbb, (byte) 0xcc}));
        assertThat(pdu.knownCommand()).contains(CommandId.SUBMIT_MULTI);
    }

    @Test
    void rejectsCommandLengthBelowHeader() {
        assertThatThrownBy(() -> PduCodec.decode(Unpooled.wrappedBuffer(HEX.parseHex(
                "0000000f" + "00000015" + "00000000" + "00000003"))))
                .isInstanceOfSatisfying(PduException.class, e -> {
                    assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVCMDLEN.code());
                    assertThat(e.sequenceNumber()).isEqualTo(3);
                    assertThat(e.toNack()).isEqualTo(new GenericNack(CommandStatus.ESME_RINVCMDLEN.code(), 3));
                });
    }

    @Test
    void rejectsCommandLengthLongerThanBuffer() {
        assertThatThrownBy(() -> PduCodec.decode(Unpooled.wrappedBuffer(HEX.parseHex(
                "00000020" + "00000015" + "00000000" + "00000003"))))
                .isInstanceOfSatisfying(PduException.class,
                        e -> assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVCMDLEN.code()));
    }

    @Test
    void reportsTruncatedBodyAsInvalidMessageLength() {
        // submit_sm that stops after the source address
        byte[] wire = HEX.parseHex("00000015" + "00000004" + "00000000" + "00000005" + "00" + "0500" + "4100");

        assertThatThrownBy(() -> PduCodec.decode(Unpooled.wrappedBuffer(wire)))
                .isInstanceOfSatisfying(PduException.class, e -> {
                    assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVMSGLEN.code());
                    assertThat(e.commandId()).isEqualTo(CommandId.SUBMIT_SM.id());
                    assertThat(e.sequenceNumber()).isEqualTo(5);
                });
    }

    @Test
    void reportsOverlongSystemIdWithTheMatchingStatus() {
        Bind bind = new Bind(CommandId.BIND_TRANSCEIVER, 1, "sixteen-chars-id", "pw", "", 0x34, Address.empty());

        assertThatThrownBy(() -> PduCodec.decode(Unpooled.wrappedBuffer(PduCodec.encode(bind))))
                .isInstanceOfSatisfying(PduException.class,
                        e -> assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVSYSID.code()));
    }

    @Test
    void reportsShortMessageLengthOverrun() {
        byte[] wire = PduCodec.encode(SubmitSm.of(1, Address.empty(), Address.empty(), 0, new byte[] {1, 2, 3}));
        wire[wire.length - 4] = 10; // sm_length claims more than remains

        assertThatThrownBy(() -> PduCodec.decode(Unpooled.wrappedBuffer(wire)))
                .isInstanceOfSatisfying(PduException.class,
                        e -> assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVMSGLEN.code()));
    }

    @Test
    void reportsTlvThatOverrunsTheBody() {
        byte[] wire = PduCodec.encode(new SubmitSmResp(0, 1, "id", List.of(Tlv.ofByte(Tlv.Tag.MESSAGE_STATE, 2))));
        wire[wire.length - 2] = 9; // TLV length field now says 9

        assertThatThrownBy(() -> PduCodec.decode(Unpooled.wrappedBuffer(wire)))
                .isInstanceOfSatisfying(PduException.class,
                        e -> assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVPARLEN.code()));
    }

    @Test
    void toleratesBindRespWithoutBodyOnError() {
        Pdu pdu = PduCodec.decode(Unpooled.wrappedBuffer(HEX.parseHex(
                "00000010" + "80000009" + "0000000e" + "00000001")));

        assertThat(pdu).isEqualTo(new BindResp(CommandId.BIND_TRANSCEIVER_RESP, 0x0e, 1, "", List.of()));
    }

    @Test
    void decodesTwoConsecutivePdusFromOneBuffer() {
        ByteBuf buf = Unpooled.buffer();
        PduCodec.encode(new EnquireLink(1), buf);
        PduCodec.encode(new EnquireLink(2), buf);

        assertThat(PduCodec.decode(buf)).isEqualTo(new EnquireLink(1));
        assertThat(PduCodec.decode(buf)).isEqualTo(new EnquireLink(2));
        assertThat(buf.isReadable()).isFalse();
    }
}
