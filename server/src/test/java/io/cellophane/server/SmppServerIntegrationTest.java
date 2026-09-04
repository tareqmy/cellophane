package io.cellophane.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppBindException;
import io.cellophane.server.api.MessageDetail;
import io.cellophane.server.api.MessagesPage;
import io.cellophane.server.smpp.SmppServer;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.Udh;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Real SMPP client (cloudhopper) against the running application, checked through the REST API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cellophane.smpp-port=0", "cellophane.accounts=app:secret:50,chaos:chaos"})
class SmppServerIntegrationTest {

    @Autowired
    private SmppServer smppServer;

    @Value("${local.server.port}")
    private int httpPort;

    private final DefaultSmppClient client = new DefaultSmppClient();
    private RestClient http;
    private SmppSession session;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://127.0.0.1:" + httpPort);
        http.delete().uri("/api/v1/messages").retrieve().toBodilessEntity();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.destroy();
        }
        client.destroy();
    }

    private SmppSessionConfiguration config(SmppBindType type, String systemId, String password) {
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setType(type);
        config.setHost("127.0.0.1");
        config.setPort(smppServer.port());
        config.setSystemId(systemId);
        config.setPassword(password);
        config.setConnectTimeout(5000);
        config.setBindTimeout(5000);
        config.getLoggingOptions().setLogBytes(false);
        return config;
    }

    private SubmitSm submit(String from, String to, byte[] text, int dataCoding) throws Exception {
        SubmitSm sm = new SubmitSm();
        sm.setSourceAddress(new Address((byte) 5, (byte) 0, from));
        sm.setDestAddress(new Address((byte) 1, (byte) 1, to));
        sm.setDataCoding((byte) dataCoding);
        sm.setShortMessage(text);
        return sm;
    }

    @Test
    void submittedMessageLandsInTheInboxWithDecodedTextAndRawPdu() throws Exception {
        session = client.bind(config(SmppBindType.TRANSCEIVER, "app", "secret"), new DefaultSmppSessionHandler());
        SubmitSmResp resp = session.submit(submit("MyApp", "8801711111111",
                "Your OTP is 482913".getBytes(StandardCharsets.ISO_8859_1), 0), 5000);

        assertThat(resp.getCommandStatus()).isZero();
        assertThat(resp.getMessageId()).hasSize(26);

        MessagesPage page = http.get().uri("/api/v1/messages?to={to}&text=otp&since=30s", "8801711111111")
                .retrieve().body(MessagesPage.class);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.messages().getFirst().id()).isEqualTo(resp.getMessageId());
        assertThat(page.messages().getFirst().text()).isEqualTo("Your OTP is 482913");
        assertThat(page.messages().getFirst().account()).isEqualTo("app");

        MessageDetail detail = http.get().uri("/api/v1/messages/{id}", resp.getMessageId()).retrieve()
                .body(MessageDetail.class);
        assertThat(detail.from().address()).isEqualTo("MyApp");
        assertThat(detail.from().ton()).isEqualTo(5);
        assertThat(detail.segments()).hasSize(1);
        MessageDetail.SegmentView segment = detail.segments().getFirst();
        assertThat(segment.pdu().dataCoding()).isZero();
        assertThat(segment.rawPduHex()).startsWith("000000").contains("00000004"); // submit_sm command id
        assertThat(segment.sessionId()).startsWith("s");
        assertThat(segment.fields()).isNotEmpty();
        assertThat(segment.fields().getFirst().name()).isEqualTo("command_length");
        assertThat(segment.fields().stream().mapToInt(MessageDetail.FieldView::length).sum())
                .isEqualTo(segment.rawPduHex().length() / 2);

        List<Map<String, Object>> sessions = http.get().uri("/api/v1/sessions").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(sessions).anySatisfy(s -> {
            assertThat(s).containsEntry("account", "app").containsEntry("bindType", "TRANSCEIVER");
            assertThat(((Number) s.get("submitted")).intValue()).isEqualTo(1);
        });
    }

    @Test
    void concatenatedPartsAreReassembledIntoOneMessage() throws Exception {
        session = client.bind(config(SmppBindType.TRANSCEIVER, "app", "secret"), new DefaultSmppSessionHandler());
        String[] parts = {"This is a long message that has been split ", "into two parts by the sender."};
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            SubmitSm sm = submit("MyApp", "8801711111111",
                    Udh.concat8(0x2A, 2, i + 1).prepend(Gsm7.encode(parts[i])), 0);
            sm.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
            ids[i] = session.submit(sm, 5000).getMessageId();
        }
        assertThat(ids[0]).isNotEqualTo(ids[1]);

        MessagesPage page = http.get().uri("/api/v1/messages").retrieve().body(MessagesPage.class);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.messages().getFirst().text()).isEqualTo(parts[0] + parts[1]);
        assertThat(page.messages().getFirst().parts()).isEqualTo(2);
        assertThat(page.messages().getFirst().partsReceived()).isEqualTo(2);
        assertThat(page.messages().getFirst().id()).isEqualTo(ids[0]);

        MessageDetail detail = http.get().uri("/api/v1/messages/{id}", ids[0]).retrieve().body(MessageDetail.class);
        assertThat(detail.concatReference()).isEqualTo(0x2A);
        assertThat(detail.segments()).extracting(MessageDetail.SegmentView::messageId).containsExactly(ids);
        assertThat(detail.segments().getFirst().udh().total()).isEqualTo(2);
        assertThat(detail.segments().getFirst().fields()).extracting(MessageDetail.FieldView::name)
                .contains("esm_class", "short_message");
    }

    @Test
    void wrongPasswordIsRejectedWithInvalidPassword() {
        assertThatThrownBy(() -> client.bind(config(SmppBindType.TRANSCEIVER, "app", "nope"),
                new DefaultSmppSessionHandler()))
                .isInstanceOfSatisfying(SmppBindException.class, e -> assertThat(e.getBindResponse()
                        .getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVPASWD));
    }

    @Test
    void unknownSystemIdIsRejectedWithInvalidSystemId() {
        assertThatThrownBy(() -> client.bind(config(SmppBindType.TRANSMITTER, "ghost", "secret"),
                new DefaultSmppSessionHandler()))
                .isInstanceOfSatisfying(SmppBindException.class, e -> assertThat(e.getBindResponse()
                        .getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVSYSID));
    }

    @Test
    void receiverBindCannotSubmit() throws Exception {
        session = client.bind(config(SmppBindType.RECEIVER, "chaos", "chaos"), new DefaultSmppSessionHandler());

        SubmitSmResp resp = session.submit(submit("A", "1", "x".getBytes(StandardCharsets.ISO_8859_1), 0), 5000);

        assertThat(resp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVBNDSTS);
        MessagesPage page = http.get().uri("/api/v1/messages").retrieve().body(MessagesPage.class);
        assertThat(page.total()).isZero();
    }

    @Test
    void unknownMessageIdIsNotFound() {
        assertThatThrownBy(() -> http.get().uri("/api/v1/messages/{id}", "nope").retrieve().toBodilessEntity())
                .hasMessageContaining("404");
    }
}
