package io.cellophane.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.EnquireLink;
import io.cellophane.server.smpp.SmppServer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** A bind that goes quiet is dropped after the idle timeout, the way an operator drops a link without keepalives. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cellophane.smpp-port=0", "cellophane.accounts=app:secret", "cellophane.idle-timeout=2s"})
class IdleTimeoutIntegrationTest {

    @Autowired
    private SmppServer smppServer;

    @Value("${local.server.port}")
    private int httpPort;

    private final DefaultSmppClient client = new DefaultSmppClient();
    private SmppSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.destroy();
        }
        client.destroy();
    }

    private int boundSessions() {
        List<Map<String, Object>> list = RestClient.create("http://127.0.0.1:" + httpPort).get().uri("/api/v1/sessions")
                .retrieve().body(new ParameterizedTypeReference<>() { });
        return list.size();
    }

    @Test
    void quietSessionIsClosedButAnActiveOneStays() throws Exception {
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setType(SmppBindType.TRANSCEIVER);
        config.setHost("127.0.0.1");
        config.setPort(smppServer.port());
        config.setSystemId("app");
        config.setPassword("secret");
        config.getLoggingOptions().setLogBytes(false);
        session = client.bind(config, new DefaultSmppSessionHandler());
        assertThat(boundSessions()).isEqualTo(1);

        // keepalives well inside the timeout keep the session alive; the slack absorbs a slow CI runner
        for (int i = 0; i < 3; i++) {
            Thread.sleep(600);
            assertThat(session.enquireLink(new EnquireLink(), 2000).getCommandStatus()).isZero();
        }
        assertThat(boundSessions()).isEqualTo(1);

        // then silence: gone within the timeout plus a margin
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(boundSessions()).isZero());
    }
}
