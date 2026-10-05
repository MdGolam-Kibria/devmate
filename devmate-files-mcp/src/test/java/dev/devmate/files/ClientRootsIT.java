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
 * {@code allow-client-roots=true}: a client's declared roots ({@code roots/list}, asked by the server during the tool
 * call) widen the sandbox for that client's session only, and every other rule still applies.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ClientRootsIT {

    private static final TestWorkspace WS = workspace();

    @Value("${local.server.port}")
    private int port;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @DynamicPropertySource
    static void sandbox(DynamicPropertyRegistry registry) {
        registry.add("devmate.filesystem.allowed-roots", () -> WS.root().toString());
        registry.add("devmate.filesystem.allow-client-roots", () -> "true");
    }

    @AfterEach
    void disconnect() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

    @Test
    void clientRootIsReadableForThatClient() {
        McpSyncClient approved = connect(WS.outside());

        CallToolResult read = call(approved, "read_file", Map.of("path", WS.outside().resolve("secret.txt").toString()));

        assertThat(read.isError()).isFalse();
        assertThat(text(read)).contains("KafkaTemplate outside the sandbox");
        assertThat(text(call(approved, "list_files", Map.of()))).contains(WS.root().toString(), WS.outside().toString());
    }

    @Test
    void otherClientsDoNotGetIt() {
        connect(WS.outside());
        McpSyncClient other = connect();

        CallToolResult read = call(other, "read_file", Map.of("path", WS.outside().resolve("secret.txt").toString()));

        assertThat(read.isError()).isTrue();
        assertThat(text(read)).isEqualTo("ACCESS_DENIED: path is outside the allowed roots");
    }

    @Test
    void denyListsStillApplyInsideAClientRoot() throws IOException {
        Files.writeString(WS.outside().resolve(".env"), "TOKEN=x\n");
        McpSyncClient approved = connect(WS.outside());

        CallToolResult read = call(approved, "read_file", Map.of("path", WS.outside().resolve(".env").toString()));

        assertThat(read.isError()).isTrue();
        assertThat(text(read)).startsWith("ACCESS_DENIED: path is excluded by policy");
    }

    @Test
    void rootsAddedLaterApplyToTheNextCall() {
        McpSyncClient client = connect();
        String secret = WS.outside().resolve("secret.txt").toString();
        assertThat(call(client, "read_file", Map.of("path", secret)).isError()).isTrue();

        client.addRoot(new McpSchema.Root(WS.outside().toUri().toString(), "outside"));

        assertThat(call(client, "read_file", Map.of("path", secret)).isError()).isFalse();
    }

    @Test
    void nonFileAndMissingRootsAreIgnored() {
        McpSyncClient client = connect();
        client.addRoot(new McpSchema.Root("https://example.com/", "web"));
        client.addRoot(new McpSchema.Root(WS.outside().resolve("missing").toUri().toString(), "missing"));

        assertThat(text(call(client, "list_files", Map.of()))).doesNotContain("example.com", "missing");
    }

    private McpSyncClient connect(Path... roots) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("roots-it", "test").build())
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
            return TestWorkspace.create(Files.createTempDirectory("devmate-roots-it"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
