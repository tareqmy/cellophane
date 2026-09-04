package io.cellophane.smpp.interop;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import io.cellophane.smpp.CommandId;
import io.cellophane.smpp.pdu.Bind;
import io.cellophane.smpp.pdu.DeliverSmResp;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.pdu.Unbind;
import io.cellophane.smpp.text.DataCoding;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.SmsText;
import io.cellophane.smpp.text.Udh;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Conformance check against cloudhopper, an independent SMPP 3.4 implementation: it binds to an SMSC built on
 * our codec, submits messages we must decode correctly, and decodes the deliver_sm we encode.
 */
class CloudhopperInteropTest {

    private final TestSmsc smsc = new TestSmsc();
    private final DefaultSmppClient client = new DefaultSmppClient();
    private final BlockingQueue<DeliverSm> delivered = new LinkedBlockingQueue<>();
    private SmppSession session;

    @BeforeEach
    void bind() throws Exception {
        int port = smsc.start();
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setType(SmppBindType.TRANSCEIVER);
        config.setHost("127.0.0.1");
        config.setPort(port);
        config.setSystemId(TestSmsc.SYSTEM_ID);
        config.setPassword("secret");
        config.setSystemType("OTP");
        config.setInterfaceVersion(SmppConstants.VERSION_3_4);
        config.setConnectTimeout(5000);
        config.setBindTimeout(5000);
        config.getLoggingOptions().setLogBytes(false);
        session = client.bind(config, new DefaultSmppSessionHandler() {
            @Override
            @SuppressWarnings("rawtypes")
            public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                if (pduRequest instanceof DeliverSm deliverSm) {
                    delivered.add(deliverSm);
                }
                return pduRequest.createResponse();
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.destroy();
        }
        client.destroy();
        smsc.close();
    }

    @Test
    void bindIsDecodedFromCloudhoppersBytes() throws Exception {
        Bind bind = smsc.next(Bind.class);

        assertThat(bind.command()).isEqualTo(CommandId.BIND_TRANSCEIVER);
        assertThat(bind.systemId()).isEqualTo(TestSmsc.SYSTEM_ID);
        assertThat(bind.password()).isEqualTo("secret");
        assertThat(bind.systemType()).isEqualTo("OTP");
        assertThat(bind.interfaceVersion()).isEqualTo(0x34);
        assertThat(session.isBound()).isTrue();
    }

    @Test
    void ucs2SubmitWithRegisteredDeliveryRoundTrips() throws Exception {
        smsc.next(Bind.class);
        String text = "আপনার OTP 482913 ✓";
        SubmitSm submit = new SubmitSm();
        submit.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
        submit.setDestAddress(new Address((byte) 1, (byte) 1, "8801711111111"));
        submit.setDataCoding(SmppConstants.DATA_CODING_UCS2);
        submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
        submit.setShortMessage(text.getBytes(StandardCharsets.UTF_16BE));

        SubmitSmResp resp = session.submit(submit, 5000);
        io.cellophane.smpp.pdu.SubmitSm received = smsc.next(io.cellophane.smpp.pdu.SubmitSm.class);

        assertThat(resp.getCommandStatus()).isZero();
        assertThat(resp.getMessageId()).isEqualTo(TestSmsc.MESSAGE_ID);
        assertThat(received.source()).isEqualTo(io.cellophane.smpp.pdu.Address.alphanumeric("MyApp"));
        assertThat(received.destination()).isEqualTo(io.cellophane.smpp.pdu.Address.international("8801711111111"));
        assertThat(received.dataCoding()).isEqualTo(DataCoding.UCS2);
        assertThat(received.wantsDeliveryReceipt()).isTrue();
        assertThat(SmsText.decode(received.dataCoding(), received.payload())).contains(text);
    }

    @Test
    void concatenatedGsm7SegmentIsDecodedWithItsUdh() throws Exception {
        smsc.next(Bind.class);
        byte[] userData = Udh.concat8(0xAB, 2, 1).prepend(Gsm7.encode("part one of two"));
        SubmitSm submit = new SubmitSm();
        submit.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
        submit.setDestAddress(new Address((byte) 1, (byte) 1, "8801711111111"));
        submit.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
        submit.setShortMessage(userData);

        session.submit(submit, 5000);
        io.cellophane.smpp.pdu.SubmitSm received = smsc.next(io.cellophane.smpp.pdu.SubmitSm.class);

        assertThat(received.hasUdh()).isTrue();
        Udh udh = Udh.parse(received.payload());
        assertThat(udh.concat()).contains(new Udh.Concat(0xAB, 2, 1));
        assertThat(SmsText.decode(0, udh.body(received.payload()))).contains("part one of two");
    }

    @Test
    void messagePayloadTlvIsPreferredOverShortMessage() throws Exception {
        smsc.next(Bind.class);
        SubmitSm submit = new SubmitSm();
        submit.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
        submit.setDestAddress(new Address((byte) 1, (byte) 1, "8801711111111"));
        byte[] longText = Gsm7.encode("x".repeat(300));
        submit.addOptionalParameter(new com.cloudhopper.smpp.tlv.Tlv(SmppConstants.TAG_MESSAGE_PAYLOAD, longText));

        session.submit(submit, 5000);
        io.cellophane.smpp.pdu.SubmitSm received = smsc.next(io.cellophane.smpp.pdu.SubmitSm.class);

        assertThat(received.shortMessage()).isEmpty();
        assertThat(received.tlv(Tlv.Tag.MESSAGE_PAYLOAD)).isPresent();
        assertThat(received.payload()).hasSize(300);
    }

    @Test
    void enquireLinkIsAnswered() throws Exception {
        smsc.next(Bind.class);

        EnquireLinkResp resp = session.enquireLink(new EnquireLink(), 5000);

        assertThat(resp.getCommandStatus()).isZero();
        assertThat(smsc.next(io.cellophane.smpp.pdu.EnquireLink.class)).isNotNull();
    }

    @Test
    void deliveryReceiptWeEncodeIsDecodedByCloudhopper() throws Exception {
        smsc.next(Bind.class);
        String receipt = "id:42 sub:001 dlvrd:001 submit date:2609041200 done date:2609041201 stat:DELIVRD err:000"
                + " text:Your OTP";
        io.cellophane.smpp.pdu.DeliverSm dlr = io.cellophane.smpp.pdu.DeliverSm.receipt(smsc.nextSequence(),
                io.cellophane.smpp.pdu.Address.international("8801711111111"),
                io.cellophane.smpp.pdu.Address.alphanumeric("MyApp"),
                receipt.getBytes(StandardCharsets.US_ASCII),
                List.of(Tlv.ofCString(Tlv.Tag.RECEIPTED_MESSAGE_ID, "42"), Tlv.ofByte(Tlv.Tag.MESSAGE_STATE, 2)));

        smsc.send(dlr);
        DeliverSm received = delivered.poll(5, TimeUnit.SECONDS);
        DeliverSmResp ack = smsc.next(DeliverSmResp.class);

        assertThat(received).isNotNull();
        assertThat(received.getEsmClass()).isEqualTo(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        assertThat(new String(received.getShortMessage(), StandardCharsets.US_ASCII)).isEqualTo(receipt);
        assertThat(received.getSourceAddress().getAddress()).isEqualTo("8801711111111");
        assertThat(received.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID).getValueAsString())
                .isEqualTo("42");
        assertThat(received.getOptionalParameter(SmppConstants.TAG_MSG_STATE).getValueAsByte()).isEqualTo((byte) 2);
        assertThat(ack.commandStatus()).isZero();
        assertThat(ack.sequenceNumber()).isEqualTo(dlr.sequenceNumber());
    }

    @Test
    void unbindIsDecodedAndAnswered() throws Exception {
        smsc.next(Bind.class);

        session.unbind(5000);

        assertThat(smsc.next(Unbind.class)).isNotNull();
        assertThat(session.isBound()).isFalse();
    }
}
