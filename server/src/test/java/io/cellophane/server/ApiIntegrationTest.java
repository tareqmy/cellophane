package io.cellophane.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cellophane.server.api.MessageSummary;
import io.cellophane.server.api.MessagesPage;
import io.cellophane.server.api.SendRequest;
import io.cellophane.server.api.SendResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** HTTP send endpoint, live stream and OpenAPI, without any SMPP client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "cellophane.smpp-port=0")
class ApiIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private RestClient http() {
        return RestClient.create("http://127.0.0.1:" + port);
    }

    @Test
    void sendEndpointStoresAndStreamsTheMessage() throws Exception {
        http().delete().uri("/api/v1/messages").retrieve().toBodilessEntity();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<java.io.InputStream> stream = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/messages/stream")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        CompletableFuture<String> firstMessageEvent = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:") && line.contains("482913")) {
                        return line.substring("data:".length()).trim();
                    }
                }
                return null;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });

        MessageSummary sent = http().post().uri("/api/v1/send").contentType(MediaType.APPLICATION_JSON)
                .body(new SendRequest("MyApp", "8801711111111", "Your OTP is 482913"))
                .retrieve().body(SendResponse.class).message();

        assertThat(sent.account()).isEqualTo("http");
        assertThat(sent.text()).isEqualTo("Your OTP is 482913");
        assertThat(sent.encoding()).isEqualTo("GSM 7-bit");
        assertThat(firstMessageEvent.get(5, TimeUnit.SECONDS)).contains(sent.id());

        MessagesPage page = http().get().uri("/api/v1/messages?to=8801711111111&text=OTP").retrieve()
                .body(MessagesPage.class);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.messages().getFirst().id()).isEqualTo(sent.id());
        stream.body().close();
    }

    @Test
    void sendRejectsMissingFields() {
        assertThatThrownBy(() -> http().post().uri("/api/v1/send").contentType(MediaType.APPLICATION_JSON)
                .body(new SendRequest("MyApp", "", "hi")).retrieve().toBodilessEntity())
                .hasMessageContaining("400");
    }

    @Test
    void badSinceIsABadRequest() {
        assertThatThrownBy(() -> http().get().uri("/api/v1/messages?since=yesterday").retrieve().toBodilessEntity())
                .hasMessageContaining("400");
    }

    @Test
    void statsAndOpenApiAreServed() {
        Map<String, Object> stats = http().get().uri("/api/v1/stats").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(stats).containsKeys("smppPort", "messages", "capacity", "sessions", "accounts");
        assertThat(((Number) stats.get("smppPort")).intValue()).isPositive();

        Map<String, Object> openapi = http().get().uri("/api/openapi.json").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(openapi.get("paths").toString()).contains("/api/v1/messages", "/api/v1/send");
    }
}
