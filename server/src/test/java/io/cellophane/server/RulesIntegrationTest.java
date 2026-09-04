package io.cellophane.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import io.cellophane.server.api.MessageDetail;
import io.cellophane.server.api.SendRequest;
import io.cellophane.server.api.SendResponse;
import io.cellophane.server.smpp.SmppServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** The operator's rules end to end: receipts arrive at a cloudhopper client, rejects come back as SMPP errors. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cellophane.smpp-port=0", "cellophane.accounts=app:secret,rx:secret"})
class RulesIntegrationTest {

    static final String RULES = """
            rules:
              - match: { to: "^88017" }
                accept: { dlr: DELIVRD, after: 100ms }
              - match: { to: "^88019" }
                accept: { dlr: UNDELIV, after: 100ms }
              - match: { text: "(?i)spam" }
                reject: ESME_RINVDSTADR
              - default:
                  accept: { dlr: none }
            """;

    @Autowired
    private SmppServer smppServer;

    @Value("${local.server.port}")
    private int httpPort;

    private final DefaultSmppClient client = new DefaultSmppClient();
    private final BlockingQueue<DeliverSm> receipts = new LinkedBlockingQueue<>();
    private final List<SmppSession> sessions = new ArrayList<>();
    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://127.0.0.1:" + httpPort);
        http.delete().uri("/api/v1/messages").retrieve().toBodilessEntity();
        http.put().uri("/api/v1/rules").contentType(MediaType.parseMediaType("application/yaml")).body(RULES)
                .retrieve().toBodilessEntity();
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(SmppSession::destroy);
        client.destroy();
    }

    private SmppSession bind(SmppBindType type, String systemId) throws Exception {
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setType(type);
        config.setHost("127.0.0.1");
        config.setPort(smppServer.port());
        config.setSystemId(systemId);
        config.setPassword("secret");
        config.setConnectTimeout(5000);
        config.setBindTimeout(5000);
        config.getLoggingOptions().setLogBytes(false);
        SmppSession session = client.bind(config, new DefaultSmppSessionHandler() {
            @Override
            @SuppressWarnings("rawtypes")
            public PduResponse firePduRequestReceived(PduRequest request) {
                if (request instanceof DeliverSm dlr) {
                    receipts.add(dlr);
                }
                return request.createResponse();
            }
        });
        sessions.add(session);
        return session;
    }

    private static SubmitSm submit(String to, String text) throws Exception {
        SubmitSm sm = new SubmitSm();
        sm.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
        sm.setDestAddress(new Address((byte) 1, (byte) 1, to));
        sm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
        sm.setShortMessage(text.getBytes(StandardCharsets.ISO_8859_1));
        return sm;
    }

    private MessageDetail detail(String id) {
        return http.get().uri("/api/v1/messages/{id}", id).retrieve().body(MessageDetail.class);
    }

    @Test
    void deliveryReceiptArrivesOnTheTransceiverAndShowsInTheTimeline() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");

        SubmitSmResp resp = session.submit(submit("8801711111111", "Your OTP is 482913"), 5000);
        DeliverSm dlr = receipts.poll(5, TimeUnit.SECONDS);

        assertThat(resp.getCommandStatus()).isZero();
        assertThat(dlr).isNotNull();
        assertThat(dlr.getEsmClass()).isEqualTo(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        assertThat(dlr.getSourceAddress().getAddress()).isEqualTo("8801711111111");
        assertThat(dlr.getDestAddress().getAddress()).isEqualTo("MyApp");
        com.cloudhopper.smpp.util.DeliveryReceipt parsed = com.cloudhopper.smpp.util.DeliveryReceipt
                .parseShortMessage(new String(dlr.getShortMessage(), StandardCharsets.ISO_8859_1),
                        org.joda.time.DateTimeZone.UTC);
        assertThat(parsed.getMessageId()).isEqualTo(resp.getMessageId());
        assertThat(parsed.getState()).isEqualTo(SmppConstants.STATE_DELIVERED);
        assertThat(dlr.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID).getValueAsString())
                .isEqualTo(resp.getMessageId());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MessageDetail d = detail(resp.getMessageId());
            assertThat(d.status()).isEqualTo("DELIVRD");
            assertThat(d.segments().getFirst().events()).extracting(MessageDetail.EventView::type)
                    .containsExactly("ACCEPTED", "DLR_SCHEDULED", "DLR_SENT", "DLR_ACKED");
            assertThat(d.segments().getFirst().events().get(1).detail()).isEqualTo("DELIVRD in 100ms");
        });
    }

    @Test
    void failedDeliveryIsReportedAsUndeliverable() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");

        SubmitSmResp resp = session.submit(submit("8801911111111", "flaky operator"), 5000);
        DeliverSm dlr = receipts.poll(5, TimeUnit.SECONDS);

        assertThat(dlr).isNotNull();
        assertThat(dlr.getOptionalParameter(SmppConstants.TAG_MSG_STATE).getValueAsByte())
                .isEqualTo(SmppConstants.STATE_UNDELIVERABLE);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(detail(resp.getMessageId()).status()).isEqualTo("UNDELIV"));
    }

    @Test
    void rejectRuleAnswersWithTheErrorAndKeepsTheMessage() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");

        SubmitSmResp resp = session.submit(submit("15551234567", "Cheap SPAM here"), 5000);

        assertThat(resp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVDSTADR);
        assertThat(resp.getMessageId()).isNull();
        Map<String, Object> page = http.get().uri("/api/v1/messages?text=spam").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(page.get("total")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = ((List<Map<String, Object>>) page.get("messages")).getFirst();
        assertThat(m.get("status")).isEqualTo("REJECTED");
        assertThat(detail((String) m.get("id")).segments().getFirst().events().getFirst().detail())
                .isEqualTo("submit_sm_resp ESME_RINVDSTADR (rule: rule 3)");
        assertThat(receipts.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void receiptWaitsUntilAReceiverOfTheAccountBinds() throws Exception {
        SmppSession tx = bind(SmppBindType.TRANSMITTER, "app");
        SubmitSmResp resp = tx.submit(submit("8801711111111", "queued"), 5000);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(detail(resp.getMessageId()).segments().getFirst().events())
                        .extracting(MessageDetail.EventView::type).contains("DLR_QUEUED"));
        assertThat(receipts.poll(200, TimeUnit.MILLISECONDS)).isNull();

        bind(SmppBindType.RECEIVER, "rx"); // wrong account: must not get it
        assertThat(receipts.poll(200, TimeUnit.MILLISECONDS)).isNull();

        bind(SmppBindType.RECEIVER, "app");
        DeliverSm dlr = receipts.poll(5, TimeUnit.SECONDS);
        assertThat(dlr).isNotNull();
        assertThat(dlr.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID).getValueAsString())
                .isEqualTo(resp.getMessageId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(detail(resp.getMessageId()).status()).isEqualTo("DELIVRD"));
    }

    @Test
    void rulesCanBeSwappedAtRuntimeAndBadOnesAreRefused() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");
        String yaml = http.get().uri("/api/v1/rules").retrieve().body(String.class);
        assertThat(yaml).contains("^88017");

        http.put().uri("/api/v1/rules").contentType(MediaType.TEXT_PLAIN)
                .body("rules:\n  - reject: ESME_RMSGQFUL\n").retrieve().toBodilessEntity();
        assertThat(session.submit(submit("8801711111111", "now?"), 5000).getCommandStatus())
                .isEqualTo(SmppConstants.STATUS_MSGQFUL);

        try {
            http.put().uri("/api/v1/rules").contentType(MediaType.TEXT_PLAIN).body("rules:\n  - blowup: 1\n")
                    .retrieve().toBodilessEntity();
            throw new AssertionError("expected 400");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
            assertThat(e.getResponseBodyAsString()).contains("unknown action 'blowup'");
        }
        assertThat(session.submit(submit("8801711111111", "still?"), 5000).getCommandStatus())
                .as("the previous rules stay in force").isEqualTo(SmppConstants.STATUS_MSGQFUL);
    }

    @Test
    void httpSendReportsTheRuleAndSimulatesTheReceipt() {
        SendResponse rejected = http.post().uri("/api/v1/send").contentType(MediaType.APPLICATION_JSON)
                .body(new SendRequest("MyApp", "1", "spam spam")).retrieve().body(SendResponse.class);
        SendResponse accepted = http.post().uri("/api/v1/send").contentType(MediaType.APPLICATION_JSON)
                .body(new SendRequest("MyApp", "8801711111111", "hello")).retrieve().body(SendResponse.class);

        assertThat(rejected.smppStatus()).isEqualTo("ESME_RINVDSTADR");
        assertThat(rejected.rule()).isEqualTo("rule 3");
        assertThat(rejected.message().status()).isEqualTo("REJECTED");
        assertThat(accepted.smppStatus()).isEqualTo("ESME_ROK");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MessageDetail d = detail(accepted.message().id());
            assertThat(d.status()).isEqualTo("DELIVRD");
            assertThat(d.segments().getFirst().events()).extracting(MessageDetail.EventView::type)
                    .contains("DLR_SIMULATED");
        });
    }
}
