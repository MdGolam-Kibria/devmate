package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api/mcp} through the whole application: HTTP → {@link McpController} → {@link McpClientService} → SDK
 * client → a real MCP server ({@link TestMcpServer}). A second configured server is down.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpControllerIT {

    private static final TestMcpServer FILES = TestMcpServer.start("files", false);
    private static final int DOWN_PORT = TestMcpServer.freePort();

    static {
        FILES.restart(FILES.readFile(), FILES.searchCode(), FILES.writeFile(),
                FILES.failing("broken", -32603, "index corrupt"));
    }

    @DynamicPropertySource
    static void servers(DynamicPropertyRegistry registry) {
        registry.add("devmate.mcp.servers.files.url", FILES::url);
        registry.add("devmate.mcp.servers.git.url", () -> "http://127.0.0.1:" + DOWN_PORT + "/mcp");
    }

    @AfterAll
    static void stopServer() {
        FILES.close();
    }

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void searchCodeKafkaThroughTheApi() throws Exception {
        var response = post("/api/mcp/tools/search_code/call", "{\"arguments\":{\"query\":\"Kafka\"}}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("tool").asString()).isEqualTo("search_code");
        assertThat(body.path("server").asString()).isEqualTo("files");
        assertThat(body.path("error").asBoolean()).isFalse();
        assertThat(body.path("errorCode").isNull()).isTrue();
        assertThat(body.path("text").asString()).contains("PaymentProducer.java", "KafkaConfig.java");
    }

    @Test
    void serversShowTheConnectedOneAndTheOneThatIsDown() throws Exception {
        JsonNode servers = json.readTree(get("/api/mcp/servers").body()).path("servers");

        assertThat(servers.size()).isEqualTo(2);
        assertThat(servers.get(0).path("name").asString()).isEqualTo("files");
        assertThat(servers.get(0).path("state").asString()).isEqualTo("CONNECTED");
        assertThat(servers.get(0).path("serverName").asString()).isEqualTo("files");
        assertThat(servers.get(0).path("tools").asInt()).isEqualTo(4);
        assertThat(servers.get(1).path("state").asString()).isEqualTo("UNAVAILABLE");
        assertThat(servers.get(1).path("error").asString()).isEqualTo("CONNECTION_FAILED");
        assertThat(servers.get(1).path("message").asString()).contains("Git MCP server is not reachable");
    }

    @Test
    void toolsListsTheDiscoveredToolsAndTheUnavailableServer() throws Exception {
        JsonNode body = json.readTree(get("/api/mcp/tools?refresh=true").body());

        assertThat(body.path("tools").valueStream().map(t -> t.path("name").asString()))
                .containsExactly("read_file", "search_code", "write_file", "broken");
        JsonNode search = body.path("tools").get(1);
        assertThat(search.path("server").asString()).isEqualTo("files");
        assertThat(search.path("readOnly").asBoolean()).isTrue();
        assertThat(search.path("inputSchema").path("required").get(0).asString()).isEqualTo("query");
        assertThat(body.path("unavailable").get(0).path("server").asString()).isEqualTo("git");
        assertThat(body.path("unavailable").get(0).path("code").asString()).isEqualTo("CONNECTION_FAILED");
    }

    @Test
    void eachFailureHasItsStatusAndCode() throws Exception {
        assertCall("read_file", "{\"arguments\":{\"path\":\"missing\"}}", 200, "TOOL_ERROR");
        assertCall("search_code", "{\"arguments\":{}}", 400, "INVALID_ARGUMENTS");
        assertCall("write_file", "{\"arguments\":{\"path\":\"a\"}}", 403, "TOOL_NOT_ALLOWED");
        assertCall("broken", "{}", 502, "SERVER_ERROR");
        assertThat(FILES.calls("write_file")).isZero();
        assertThat(FILES.calls("search_code")).isLessThanOrEqualTo(1);   // only the Kafka test's call
    }

    @Test
    void unknownToolWhileAServerIsDownIsAnOutage() throws Exception {
        var response = post("/api/mcp/tools/git_log/call", "{}");

        assertThat(response.statusCode()).isEqualTo(503);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("error").asString()).isEqualTo("MCP_UNAVAILABLE");
        assertThat(body.path("code").asString()).isEqualTo("CONNECTION_FAILED");
        assertThat(body.path("server").asString()).isEqualTo("git");
    }

    @Test
    void rejectsBadRequestsBeforeTouchingMcp() throws Exception {
        assertThat(post("/api/mcp/tools/bad%20name/call", "{}").statusCode()).isEqualTo(400);
        assertThat(post("/api/mcp/tools/" + "a".repeat(129) + "/call", "{}").statusCode()).isEqualTo(400);
        // a cross-site form can only send these content types: refused, so no CSRF through this API
        assertThat(send("/api/mcp/tools/read_file/call", "path=a", "application/x-www-form-urlencoded").statusCode())
                .isEqualTo(415);
        assertThat(send("/api/mcp/tools/read_file/call", "{\"arguments\":{}}", "text/plain").statusCode())
                .isEqualTo(415);
        assertThat(get("/api/mcp/tools/read_file/call").statusCode()).isEqualTo(405);
    }

    private void assertCall(String tool, String body, int status, String code) throws Exception {
        var response = post("/api/mcp/tools/" + tool + "/call", body);
        assertThat(response.statusCode()).as(tool).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("errorCode").asString()).as(tool).isEqualTo(code);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(path, body, "application/json");
    }

    private HttpResponse<String> send(String path, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
