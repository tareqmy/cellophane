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
import io.cellophane.server.api.MessagesPage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The image a user actually runs: start it the way the README says, bind a real SMPP client to it, send a message,
 * receive the receipt, read it back over HTTP. Catches what unit tests on a full JDK cannot, such as modules
 * missing from the jlinked JRE inside the container. Runs only under the {@code image} Maven profile
 * ({@code ./mvnw -Pimage verify}) because it needs Docker.
 */
class ImageSmokeIT {

    private static final String IMAGE = System.getProperty("cellophane.image", "cellophane/cellophane:0.2.0-SNAPSHOT");

    private static GenericContainer<?> cellophane;
    private static RestClient http;
    private static int smppPort;

    @BeforeAll
    static void start() {
        cellophane = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(2775, 8025)
                .withEnv("CELLOPHANE_ACCOUNTS", "app:secret:50")
                .waitingFor(Wait.forHttp("/api/v1/stats").forPort(8025).withStartupTimeout(Duration.ofSeconds(90)))
                .withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("container")));
        cellophane.start();
        http = RestClient.create("http://" + cellophane.getHost() + ":" + cellophane.getMappedPort(8025));
        smppPort = cellophane.getMappedPort(2775);
    }

    @AfterAll
    static void stop() {
        if (cellophane != null) {
            cellophane.stop();
        }
    }

    @Test
    void servesTheUiAndTheApi() {
        String index = http.get().uri("/").retrieve().body(String.class);
        assertThat(index).contains("<title>Cellophane</title>");

        Map<String, Object> stats = http.get().uri("/api/v1/stats").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(stats).containsEntry("smppPort", 2775).containsEntry("accounts", java.util.List.of("app"));

        Map<String, Object> openapi = http.get().uri("/api/openapi.json").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(openapi.get("paths").toString()).contains("/api/v1/messages", "/api/v1/rules", "/api/v1/mo");
    }

    @Test
    void realSmppClientBindsSendsAndGetsAReceiptThroughTheContainer() throws Exception {
        http.put().uri("/api/v1/rules").contentType(MediaType.parseMediaType("application/yaml"))
                .body("rules:\n  - default:\n      accept: { dlr: DELIVRD, after: 100ms }\n").retrieve()
                .toBodilessEntity();
        BlockingQueue<DeliverSm> receipts = new LinkedBlockingQueue<>();
        DefaultSmppClient client = new DefaultSmppClient();
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setType(SmppBindType.TRANSCEIVER);
        config.setHost(cellophane.getHost());
        config.setPort(smppPort);
        config.setSystemId("app");
        config.setPassword("secret");
        config.setConnectTimeout(10_000);
        config.setBindTimeout(10_000);
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
        try {
            SubmitSm sm = new SubmitSm();
            sm.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
            sm.setDestAddress(new Address((byte) 1, (byte) 1, "8801711111111"));
            sm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
            sm.setShortMessage("Your OTP is 482913".getBytes(StandardCharsets.ISO_8859_1));

            SubmitSmResp resp = session.submit(sm, 10_000);
            assertThat(resp.getCommandStatus()).isZero();

            DeliverSm receipt = receipts.poll(10, TimeUnit.SECONDS);
            assertThat(receipt).isNotNull();
            assertThat(receipt.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID).getValueAsString())
                    .isEqualTo(resp.getMessageId());

            MessagesPage page = http.get().uri("/api/v1/messages?to=8801711111111&text=OTP&since=60s").retrieve()
                    .body(MessagesPage.class);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.messages().getFirst().id()).isEqualTo(resp.getMessageId());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                MessageDetail detail = http.get().uri("/api/v1/messages/{id}", resp.getMessageId()).retrieve()
                        .body(MessageDetail.class);
                assertThat(detail.status()).isEqualTo("DELIVRD");
                assertThat(detail.segments().getFirst().events()).extracting(MessageDetail.EventView::type)
                        .contains("DLR_SENT", "DLR_ACKED");
            });
            session.unbind(5_000);
        } finally {
            session.destroy();
            client.destroy();
        }
    }
}
