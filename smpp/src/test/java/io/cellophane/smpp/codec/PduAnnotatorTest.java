package io.cellophane.smpp.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.cellophane.smpp.CommandId;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.Bind;
import io.cellophane.smpp.pdu.BindResp;
import io.cellophane.smpp.pdu.DeliverSm;
import io.cellophane.smpp.pdu.EnquireLink;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.SubmitSmResp;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.Udh;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class PduAnnotatorTest {

    private static void assertContiguous(List<PduAnnotator.Field> fields, int totalLength) {
        int pos = 0;
        for (PduAnnotator.Field f : fields) {
            assertThat(f.offset()).as("field %s starts where the previous ended", f.name()).isEqualTo(pos);
            pos += f.length();
        }
        assertThat(pos).as("fields cover the whole PDU").isEqualTo(totalLength);
    }

    @Test
    void annotatesEveryFieldOfASubmitSmWithTlvs() {
        SubmitSm pdu = new SubmitSm(7, "OTP", Address.alphanumeric("MyApp"), Address.international("8801711111111"),
                0x40, 0, 1, "", "000000000100000R", 1, 0, 8, 0,
                Udh.concat8(0xAB, 2, 1).prepend("Hi".getBytes(StandardCharsets.UTF_16BE)),
                List.of(Tlv.ofShort(Tlv.Tag.SAR_MSG_REF_NUM, 0xAB), Tlv.ofByte(Tlv.Tag.SAR_TOTAL_SEGMENTS, 2),
                        Tlv.ofCString(Tlv.Tag.RECEIPTED_MESSAGE_ID, "x1")));
        byte[] wire = PduCodec.encode(pdu);

        List<PduAnnotator.Field> fields = PduAnnotator.annotate(wire);

        assertContiguous(fields, wire.length);
        assertThat(fields).extracting(PduAnnotator.Field::name).containsExactly(
                "command_length", "command_id", "command_status", "sequence_number",
                "service_type", "source_addr_ton", "source_addr_npi", "source_addr",
                "dest_addr_ton", "dest_addr_npi", "destination_addr",
                "esm_class", "protocol_id", "priority_flag", "schedule_delivery_time", "validity_period",
                "registered_delivery", "replace_if_present_flag", "data_coding", "sm_default_msg_id",
                "sm_length", "short_message", "sar_msg_ref_num", "sar_total_segments", "receipted_message_id");
        assertThat(fields.get(1).value()).isEqualTo("0x00000004 submit_sm");
        assertThat(fields.get(2).value()).contains("ESME_ROK");
        assertThat(fields.get(3).value()).isEqualTo("7");
        assertThat(fields.get(5).value()).isEqualTo("5 (alphanumeric)");
        assertThat(fields.get(7).value()).isEqualTo("\"MyApp\"");
        assertThat(fields.get(11).value()).isEqualTo("0x40 UDHI (user data header present)");
        assertThat(fields.get(16).value()).isEqualTo("0x01 final receipt requested");
        assertThat(fields.get(18).value()).isEqualTo("0x08 UCS-2");
        assertThat(fields.get(20).value()).isEqualTo("10 bytes");
        assertThat(fields.get(21).length()).isEqualTo(10);
        assertThat(fields.get(22).value()).isEqualTo("tag 0x020C, 2 bytes: 171");
        assertThat(fields.get(23).value()).isEqualTo("tag 0x020E, 1 byte: 2");
        assertThat(fields.get(24).value()).isEqualTo("tag 0x001E, 3 bytes: \"x1\"");
    }

    @Test
    void annotatesBindAndResponses() {
        byte[] bind = PduCodec.encode(Bind.transceiver(1, "cellophane", "cellophane"));
        List<PduAnnotator.Field> fields = PduAnnotator.annotate(bind);
        assertContiguous(fields, bind.length);
        assertThat(fields).extracting(PduAnnotator.Field::name).contains("system_id", "password",
                "interface_version", "address_range");
        assertThat(fields.get(7).value()).isEqualTo("0x34 (SMPP 3.4)");

        byte[] resp = PduCodec.encode(new BindResp(CommandId.BIND_TRANSCEIVER_RESP, 0, 1, "smsc",
                List.of(Tlv.ofByte(Tlv.Tag.SC_INTERFACE_VERSION, 0x34))));
        fields = PduAnnotator.annotate(resp);
        assertContiguous(fields, resp.length);
        assertThat(fields.getLast().name()).isEqualTo("sc_interface_version");

        byte[] errorResp = PduCodec.encode(new BindResp(CommandId.BIND_TRANSCEIVER_RESP, 0x0E, 1, "", List.of()));
        assertThat(PduAnnotator.annotate(errorResp)).hasSize(4).last()
                .extracting(PduAnnotator.Field::name).isEqualTo("sequence_number");
        assertThat(PduAnnotator.annotate(errorResp).get(2).value()).contains("ESME_RINVPASWD");

        byte[] submitResp = PduCodec.encode(new SubmitSmResp(0, 3, "42", List.of()));
        assertThat(PduAnnotator.annotate(submitResp).getLast().value()).isEqualTo("\"42\"");
    }

    @Test
    void annotatesDeliveryReceiptAndHeaderOnlyPdus() {
        byte[] dlr = PduCodec.encode(DeliverSm.receipt(9, Address.international("1"), Address.alphanumeric("A"),
                Gsm7.encode("id:42 stat:DELIVRD"), List.of(Tlv.ofByte(Tlv.Tag.MESSAGE_STATE, 2))));
        List<PduAnnotator.Field> fields = PduAnnotator.annotate(dlr);
        assertContiguous(fields, dlr.length);
        assertThat(fields.get(11).value()).isEqualTo("0x04 delivery receipt");
        assertThat(fields.getLast().value()).isEqualTo("tag 0x0427, 1 byte: 2 (DELIVERED)");

        byte[] enquire = PduCodec.encode(new EnquireLink(5));
        assertThat(PduAnnotator.annotate(enquire)).hasSize(4);
        assertThat(PduAnnotator.annotate(enquire).get(1).value()).isEqualTo("0x00000015 enquire_link");
    }

    @Test
    void marksTruncatedAndTrailingBytesInsteadOfThrowing() {
        byte[] full = PduCodec.encode(SubmitSm.of(1, Address.alphanumeric("A"), Address.international("1"), 0,
                Gsm7.encode("hello")));

        byte[] cut = Arrays.copyOf(full, 24); // stops inside destination_addr, before its NUL
        List<PduAnnotator.Field> fields = PduAnnotator.annotate(cut);
        assertContiguous(fields, cut.length);
        assertThat(fields.getLast().name()).isEqualTo("truncated");
        assertThat(fields.getLast().value()).contains("destination_addr");

        byte[] shortHeader = Arrays.copyOf(full, 6);
        assertThat(PduAnnotator.annotate(shortHeader)).extracting(PduAnnotator.Field::name)
                .containsExactly("command_length", "truncated");

        byte[] unknown = PduCodec.encode(new io.cellophane.smpp.pdu.UnknownPdu(0x103, 0, 1, new byte[] {1, 2}));
        assertThat(PduAnnotator.annotate(unknown).getLast().name()).isEqualTo("body");

        byte[] withTrailing = Arrays.copyOf(PduCodec.encode(new EnquireLink(1)), 18);
        assertThat(PduAnnotator.annotate(withTrailing).getLast().name()).isEqualTo("trailing bytes");

        assertThat(PduAnnotator.annotate(new byte[0])).extracting(PduAnnotator.Field::name)
                .containsExactly("truncated");
    }
}
