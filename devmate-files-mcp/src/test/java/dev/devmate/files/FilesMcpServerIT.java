package dev.devmate.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over the real protocol: boots the server on a random port and drives it with the official MCP Java SDK
 * client (Streamable HTTP): initialize → tools/list → tools/call. This is the M1 definition of done.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilesMcpServerIT {

    private static final TestWorkspace WS = createWorkspace();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    private McpSyncClient client;
    private McpSchema.InitializeResult initialize;

    @DynamicPropertySource
    static void sandbox(DynamicPropertyRegistry registry) {
        registry.add("devmate.filesystem.allowed-roots", () -> WS.root().toString());
        registry.add("devmate.filesystem.max-file-size", () -> TestWorkspace.MAX_FILE_SIZE.toBytes() + "B");
        registry.add("devmate.http.allowed-origins", () -> "http://localhost:6274");
    }

    @BeforeAll
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build();
        client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("devmate-files-it", "test").build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        initialize = client.initialize();
    }

    @AfterAll
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void initializeAdvertisesServerAndToolsCapabilityOnly() {
        assertThat(initialize.serverInfo().name()).isEqualTo("devmate-files");
        assertThat(initialize.capabilities().tools()).isNotNull();
        assertThat(initialize.capabilities().resources()).isNull();
        assertThat(initialize.capabilities().prompts()).isNull();
        assertThat(initialize.instructions()).contains("Read-only");
    }

    @Test
    void toolsListExposesExactlyTheThreeReadOnlyTools() {
        var tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name)
                .containsExactlyInAnyOrder("list_files", "read_file", "search_code");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
        });
        Tool search = tools.stream().filter(t -> t.name().equals("search_code")).findFirst().orElseThrow();
        assertThat(search.inputSchema().get("properties")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsOnlyKeys("query", "path", "glob");
        assertThat(search.inputSchema().get("required")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("query");
    }

    @Test
    void searchCodeFindsKafkaTemplateUsages() {
        JsonNode result = callOk("search_code", Map.of("query", "KafkaTemplate"));

        assertThat(result.get("matches").valueStream().map(m -> Path.of(m.get("path").asString()).getFileName().toString()))
                .containsExactlyInAnyOrder("PaymentProducer.java", "PaymentService.java", "KafkaConfig.java");
        assertThat(result.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void readFileReturnsContent() {
        JsonNode result = callOk("read_file", Map.of("path", "src/main/java/com/acme/KafkaConfig.java"));

        assertThat(result.get("content").asString()).contains("@Bean KafkaTemplate");
    }

    @Test
    void listFilesListsRootsThenDirectory() {
        JsonNode roots = callOk("list_files", Map.of());
        assertThat(roots.get("entries").get(0).get("path").asString()).isEqualTo(WS.root().toString());

        JsonNode root = callOk("list_files", Map.of("path", WS.root().toString()));
        assertThat(root.get("entries").valueStream().map(e -> e.get("name").asString()))
                .contains("src", "README.md")
                .doesNotContain(".env", "target");
    }

    @Test
    void sandboxViolationsAreToolErrorsNotProtocolErrors() {
        assertToolError("read_file", Map.of("path", "../outside/secret.txt"), "ACCESS_DENIED");
        assertToolError("read_file", Map.of("path", "/etc/passwd"), "ACCESS_DENIED");
        assertToolError("read_file", Map.of("path", "escape.txt"), "ACCESS_DENIED");
        assertToolError("read_file", Map.of("path", ".env"), "ACCESS_DENIED");
        assertToolError("read_file", Map.of("path", "logo.png"), "BINARY_FILE");
        assertToolError("read_file", Map.of("path", "big.txt"), "TOO_LARGE");
        assertToolError("read_file", Map.of("path", "nope.txt"), "NOT_FOUND");
        assertToolError("search_code", Map.of("query", " "), "INVALID_ARGUMENT");
    }

    @Test
    void unknownToolIsAProtocolError() {
        assertThatThrownBy(() -> client.callTool(request("delete_everything", Map.of())))
                .isInstanceOf(McpError.class);
    }

    @Test
    void rejectsDisallowedBrowserOrigin() throws Exception {
        assertThat(postInitializeWithOrigin("http://evil.example").statusCode()).isEqualTo(403);
        assertThat(postInitializeWithOrigin("http://localhost:6274").statusCode()).isEqualTo(200);
    }

    @Test
    void healthIsUp() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private JsonNode callOk(String tool, Map<String, Object> args) {
        CallToolResult result = client.callTool(request(tool, args));
        assertThat(result.isError()).as("isError for %s: %s", tool, result.content()).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text(result));
    }

    private void assertToolError(String tool, Map<String, Object> args, String reason) {
        CallToolResult result = client.callTool(request(tool, args));
        assertThat(result.isError()).as("%s %s", tool, args).isTrue();
        assertThat(text(result)).startsWith(reason + ": ").doesNotContain("\n", WS.outside().toString());
        assertThat(result.content()).hasSize(1);
    }

    private static McpSchema.CallToolRequest request(String tool, Map<String, Object> args) {
        return McpSchema.CallToolRequest.builder(tool).arguments(args).build();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private HttpResponse<String> postInitializeWithOrigin(String origin) throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"origin-test","version":"1"}}}""";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static TestWorkspace createWorkspace() {
        try {
            return TestWorkspace.create(Files.createTempDirectory("devmate-files-it"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
