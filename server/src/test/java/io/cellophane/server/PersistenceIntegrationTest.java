package io.cellophane.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cellophane.server.api.MessageDetail;
import io.cellophane.server.api.MessagesPage;
import io.cellophane.server.api.SendRequest;
import io.cellophane.server.api.SendResponse;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** With CELLOPHANE_DB set, the inbox is the same after a restart, receipts and timelines included. */
class PersistenceIntegrationTest {

    @TempDir
    Path dir;

    private ConfigurableApplicationContext start() {
        SpringApplication app = new SpringApplication(CellophaneApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        // Command-line form: application.yaml's ${CELLOPHANE_HTTP_PORT:8025} would beat default properties.
        return app.run("--server.port=0", "--cellophane.smpp-port=0",
                "--cellophane.db=" + dir.resolve("inbox.db"));
    }

    private static RestClient http(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return RestClient.create("http://127.0.0.1:" + port);
    }

    @Test
    void messagesSurviveARestart() {
        String id;
        try (ConfigurableApplicationContext first = start()) {
            RestClient http = http(first);
            SendResponse sent = http.post().uri("/api/v1/send").contentType(MediaType.APPLICATION_JSON)
                    .body(new SendRequest("MyApp", "+8801711111111", "Your OTP is 482913")).retrieve()
                    .body(SendResponse.class);
            id = sent.message().id();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                    http.get().uri("/api/v1/messages/{id}", sent.message().id()).retrieve().body(MessageDetail.class)
                            .status()).isEqualTo("DELIVRD"));
        }

        try (ConfigurableApplicationContext second = start()) {
            RestClient http = http(second);
            MessagesPage page = http.get().uri("/api/v1/messages").retrieve().body(MessagesPage.class);
            assertThat(page.total()).isEqualTo(1);
            MessageDetail detail = http.get().uri("/api/v1/messages/{id}", id).retrieve().body(MessageDetail.class);
            assertThat(detail.text()).isEqualTo("Your OTP is 482913");
            assertThat(detail.status()).isEqualTo("DELIVRD");
            assertThat(detail.segments().getFirst().events()).extracting(e -> e.type())
                    .contains("ACCEPTED", "DLR_SCHEDULED", "DLR_SIMULATED");
            assertThat(detail.segments().getFirst().rawPduHex()).isNotEmpty();

            http.delete().uri("/api/v1/messages").retrieve().toBodilessEntity();
        }

        try (ConfigurableApplicationContext third = start()) {
            assertThat(http(third).get().uri("/api/v1/messages").retrieve().body(MessagesPage.class).total())
                    .as("a clear is persisted too").isZero();
        }
    }
}
