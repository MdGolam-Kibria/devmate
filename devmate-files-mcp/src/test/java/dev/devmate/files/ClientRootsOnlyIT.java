package dev.devmate.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The out-of-the-box setup (e.g. started from the IDE): no {@code allowed-roots}, {@code allow-client-roots} left at
 * its {@code application.yml} default. The server starts, sees nothing, and a client reads only what it declares as
 * roots (DevMate: folders approved in the "Allow" popup).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ClientRootsOnlyIT {

    private static final TestWorkspace WS = workspace();

    @Value("${local.server.port}")
    private int port;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @DynamicPropertySource
    static void sandbox(DynamicPropertyRegistry registry) {
        registry.add("devmate.filesystem.allowed-roots", () -> "");
    }

    @AfterEach
    void disconnect() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

    @Test
    void withoutApprovalNothingIsVisible() {
        McpSyncClient client = connect();

        assertThat(text(call(client, "list_files", Map.of()))).contains("\"entries\":[]");
        assertThat(text(call(client, "read_file", Map.of("path", WS.root().resolve("README.md").toString()))))
                .isEqualTo("ACCESS_DENIED: path is outside the allowed roots");
        assertThat(text(call(client, "read_file", Map.of("path", "README.md"))))
                .isEqualTo("ACCESS_DENIED: path is outside the allowed roots");
    }

    @Test
    void approvedFolderIsReadableAndDenyListsStillApply() throws IOException {
        Files.writeString(WS.root().resolve(".env"), "TOKEN=x\n");
        McpSyncClient client = connect(WS.root());

        CallToolResult read = call(client, "read_file", Map.of("path", WS.root().resolve("README.md").toString()));
        assertThat(read.isError()).isFalse();
        assertThat(text(call(client, "read_file", Map.of("path", WS.root().resolve(".env").toString()))))
                .startsWith("ACCESS_DENIED: path is excluded by policy");
        assertThat(text(call(client, "read_file", Map.of("path", WS.outside().resolve("secret.txt").toString()))))
                .isEqualTo("ACCESS_DENIED: path is outside the allowed roots");
    }

    private McpSyncClient connect(Path... roots) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("roots-only-it", "test").build())
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .roots(Arrays.stream(roots).map(r -> new McpSchema.Root(r.toUri().toString(), "approved")).toList())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        client.initialize();
        clients.add(client);
        return client;
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static TestWorkspace workspace() {
        try {
            return TestWorkspace.create(Files.createTempDirectory("devmate-roots-only-it"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
