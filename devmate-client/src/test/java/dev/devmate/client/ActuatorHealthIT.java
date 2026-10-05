package dev.devmate.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/** Boots the real application on a random port and exercises it over HTTP. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ActuatorHealthIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthEndpointsReportUp(String path) throws Exception {
        var response = get(path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthHidesComponentDetailsByDefault() throws Exception {
        assertThat(get("/actuator/health").body()).doesNotContain("components", "diskSpace");
    }

    @Test
    void infoExposesBuildMetadata() throws Exception {
        var response = get("/actuator/info");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"artifact\":\"devmate-client\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump"})
    void sensitiveActuatorEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(path).statusCode()).isEqualTo(404);
    }

    @Test
    void responsesCarryRequestId() throws Exception {
        var response = get("/actuator/health");

        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
