package io.cellophane.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import io.cellophane.server.api.MessageDetail;
import io.cellophane.server.smpp.SmppServer;
import io.cellophane.smpp.text.Gsm7;
import io.cellophane.smpp.text.Udh;

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

/** Throttle, latency and disconnect rules, MO injection and stats, against a cloudhopper client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cellophane.smpp-port=0", "cellophane.accounts=app:secret,other:secret,narrow:secret:2"})
class ChaosIntegrationTest {

    static final String RULES = """
            rules:
              - name: limited
                match: { to: "^88015" }
                throttle: { tps: 2, then: ESME_RTHROTTLED }
              - name: slow
                match: { to: "^88016" }
                latency: 400ms
              - name: flaky-link
                match: { text: "^drop" }
                disconnect: { after: 2 }
              - default:
                  accept: { dlr: none }
            """;

    @Autowired
    private SmppServer smppServer;

    @Value("${local.server.port}")
    private int httpPort;

    private final DefaultSmppClient client = new DefaultSmppClient();
    private final BlockingQueue<DeliverSm> delivered = new LinkedBlockingQueue<>();
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
        config.setRequestExpiryTimeout(5000);
        config.setWindowSize(10);
        config.getLoggingOptions().setLogBytes(false);
        SmppSession session = client.bind(config, new DefaultSmppSessionHandler() {
            @Override
            @SuppressWarnings("rawtypes")
            public PduResponse firePduRequestReceived(PduRequest request) {
                if (request instanceof DeliverSm mo) {
                    delivered.add(mo);
                }
                return request.createResponse();
            }
        });
        sessions.add(session);
        return session;
    }

    private int boundSessions() {
        List<Map<String, Object>> list = http.get().uri("/api/v1/sessions").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return (int) list.stream().filter(s -> s.get("bindType") != null).count();
    }

    private static SubmitSm submit(String to, String text) throws Exception {
        SubmitSm sm = new SubmitSm();
        sm.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
        sm.setDestAddress(new Address((byte) 1, (byte) 1, to));
        sm.setShortMessage(text.getBytes(StandardCharsets.ISO_8859_1));
        return sm;
    }

    @Test
    void throttleAnswersRthrottledAboveTheLimit() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            statuses.add(session.submit(submit("8801511111111", "burst " + i), 5000).getCommandStatus());
        }

        assertThat(statuses).filteredOn(s -> s == SmppConstants.STATUS_OK).hasSizeBetween(2, 4)
                .as("2 per second; the burst may straddle a second boundary");
        assertThat(statuses).contains(SmppConstants.STATUS_THROTTLED);
        Map<String, Object> stats = http.get().uri("/api/v1/stats").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> byStatus = (Map<String, Object>) stats.get("byStatus");
        assertThat(byStatus).containsKey("REJECTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) stats.get("totals");
        // totals are since start and shared with the other tests in this context
        assertThat(((Number) totals.get("submitted")).intValue()).isGreaterThanOrEqualTo(5);
        assertThat(((Number) totals.get("rejected")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) totals.get("tps")).doubleValue()).isPositive();
    }

    @Test
    void latencyDelaysTheResponseAndShowsInTheTimeline() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");

        long start = System.nanoTime();
        var resp = session.submit(submit("8801611111111", "slow lane"), 5000);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.getCommandStatus()).isZero();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(380);
        MessageDetail d = http.get().uri("/api/v1/messages/{id}", resp.getMessageId()).retrieve()
                .body(MessageDetail.class);
        assertThat(d.segments().getFirst().events()).extracting(MessageDetail.EventView::type).contains("DELAYED");
        assertThat(d.segments().getFirst().events()).extracting(MessageDetail.EventView::detail)
                .contains("submit_sm_resp held for 400ms (rules: slow)");
    }

    @Test
    void disconnectDropsTheLinkAfterTheNthMatchingMessage() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "app");

        assertThat(session.submit(submit("15551234567", "drop me 1"), 5000).getCommandStatus()).isZero();
        var second = session.submit(submit("15551234567", "drop me 2"), 5000);
        assertThat(second.getCommandStatus()).as("the second is still answered").isZero();

        // cloudhopper keeps reporting "bound" after a peer close; the server side knows the session is gone
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(boundSessions()).isZero());
        assertThatThrownBy(() -> session.submit(submit("15551234567", "drop me 3"), 2000))
                .isInstanceOfAny(SmppChannelException.class, SmppTimeoutException.class);
        MessageDetail d = http.get().uri("/api/v1/messages/{id}", second.getMessageId()).retrieve()
                .body(MessageDetail.class);
        assertThat(d.segments().getFirst().events()).extracting(MessageDetail.EventView::type)
                .contains("DISCONNECTED");

        SmppSession again = bind(SmppBindType.TRANSCEIVER, "app");
        assertThat(again.submit(submit("15551234567", "drop me 1 again"), 5000).getCommandStatus())
                .as("a fresh bind starts counting again").isZero();
        assertThat(again.isBound()).isTrue();
    }

    @Test
    void submitsBeyondTheAccountWindowGetMessageQueueFull() throws Exception {
        SmppSession session = bind(SmppBindType.TRANSCEIVER, "narrow"); // window 2
        List<com.cloudhopper.commons.util.windowing.WindowFuture<Integer, PduRequest, PduResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            // slow lane: the response is held for 400ms, so these four overlap on the wire
            futures.add(session.sendRequestPdu(submit("8801611111111", "burst " + i), 5000, false));
        }
        List<Integer> statuses = new ArrayList<>();
        for (var f : futures) {
            f.await(5000);
            statuses.add(f.getResponse().getCommandStatus());
        }

        assertThat(statuses).filteredOn(st -> st == SmppConstants.STATUS_OK).hasSize(2);
        assertThat(statuses).filteredOn(st -> st == SmppConstants.STATUS_MSGQFUL).hasSize(2);
        Map<String, Object> page = http.get().uri("/api/v1/messages?status=rejected&account=narrow").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(page.get("total")).isEqualTo(2);

        // once the window drains, the session is usable again
        assertThat(session.submit(submit("15551234567", "after"), 5000).getCommandStatus()).isZero();
    }

    @Test
    void moInjectionReachesTheReceiverSplitIntoParts() throws Exception {
        bind(SmppBindType.RECEIVER, "app");
        String longText = "The quick brown fox jumps over the lazy dog. ".repeat(5).trim(); // 224 GSM chars

        Map<String, Object> sent = http.post().uri("/api/v1/mo").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", "8801711111111", "to", "MyApp", "text", longText)).retrieve()
                .body(new ParameterizedTypeReference<>() { });

        assertThat(sent).containsEntry("account", "app").containsEntry("parts", 2);
        DeliverSm p1 = delivered.poll(5, TimeUnit.SECONDS);
        DeliverSm p2 = delivered.poll(5, TimeUnit.SECONDS);
        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        assertThat(p1.getSourceAddress().getAddress()).isEqualTo("8801711111111");
        assertThat(p1.getDestAddress().getAddress()).isEqualTo("MyApp");
        assertThat(p1.getEsmClass() & SmppConstants.ESM_CLASS_UDHI_MASK).isNotZero();
        Udh udh1 = Udh.parse(p1.getShortMessage());
        assertThat(udh1.concat().orElseThrow().total()).isEqualTo(2);
        String joined = Gsm7.decode(udh1.body(p1.getShortMessage()))
                + Gsm7.decode(Udh.parse(p2.getShortMessage()).body(p2.getShortMessage()));
        assertThat(joined).isEqualTo(longText);

        Map<String, Object> single = http.post().uri("/api/v1/mo").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", "Bank", "to", "MyApp", "text", "STOP", "account", "app")).retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(single).containsEntry("parts", 1);
        DeliverSm stop = delivered.poll(5, TimeUnit.SECONDS);
        assertThat(stop).isNotNull();
        assertThat(new String(stop.getShortMessage(), StandardCharsets.ISO_8859_1)).isEqualTo("STOP");
        assertThat(stop.getSourceAddress().getTon()).isEqualTo((byte) 5);
    }

    @Test
    void moInjectionWithoutAReceiverIsAConflict() throws Exception {
        assertThatThrownBy(() -> http.post().uri("/api/v1/mo").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", "1", "to", "2", "text", "hi")).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.getResponseBodyAsString()).contains("no receiver or transceiver is bound");
                });

        bind(SmppBindType.TRANSMITTER, "app");
        assertThatThrownBy(() -> http.post().uri("/api/v1/mo").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", "1", "to", "2", "text", "hi", "account", "app")).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getResponseBodyAsString()).contains("account 'app'"));

        bind(SmppBindType.RECEIVER, "app");
        bind(SmppBindType.RECEIVER, "other");
        assertThatThrownBy(() -> http.post().uri("/api/v1/mo").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", "1", "to", "2", "text", "hi")).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getResponseBodyAsString()).contains("several accounts"));
    }
}
