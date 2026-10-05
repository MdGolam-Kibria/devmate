package dev.devmate.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** HTTP contract of POST /api/chat, with the model replaced by a mock. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChatControllerIT {

    @MockitoBean
    LlmClient llm;

    @Value("${local.server.port}")
    int port;

    @Test
    void returnsReplyWithProviderModelAndUsage() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("Kafka is used in PaymentProducer", "claude-sonnet-5-5", 20, 7));

        var response = post("{\"message\":\"where is kafka used?\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"provider\":\"anthropic\"", "\"model\":\"claude-sonnet-5-5\"",
                        "\"reply\":\"Kafka is used in PaymentProducer\"", "\"inputTokens\":20", "\"outputTokens\":7");
    }

    @Test
    void providerFailureIs502WithSafeMessage() throws Exception {
        when(llm.chat(any())).thenThrow(new LlmException("LLM provider 'anthropic' failed: 401 Unauthorized", null));

        var response = post("{\"message\":\"hi\"}");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("LLM_UNAVAILABLE", "401 Unauthorized");
    }

    @Test
    void blankMessageIs400() throws Exception {
        var response = post("{\"message\":\"  \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST");
    }

    private HttpResponse<String> post(String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
