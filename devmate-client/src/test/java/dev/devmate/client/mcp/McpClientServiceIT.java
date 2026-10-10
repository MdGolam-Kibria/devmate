package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.devmate.client.mcp.McpServerStatus.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.util.unit.DataSize;

/**
 * The MCP client end to end over the real protocol (Streamable HTTP) against SDK servers on embedded Tomcat:
 * connect → initialize → discover → register → execute, and every failure category.
 */
class McpClientServiceIT {

    private final List<TestMcpServer> servers = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ToolRegistry registry;
    private McpClientService client;

    @AfterEach
    void stop() {
        if (registry != null) {
            registry.close();
        }
        servers.forEach(TestMcpServer::close);
    }

    // ── Definition of done: connect → initialize → discover → register → execute → result ──────────

    @Test
    void connectsInitializesDiscoversRegistersAndExecutesSearchCodeWithoutAnLlm() {
        var files = server("files", false);
        files.restart(files.readFile(), files.searchCode(), files.writeFile());
        client = clientFor(Map.of("files", files.url()));

        List<McpServerStatus> before = clientStatusWithoutConnecting();
        assertThat(before).singleElement().extracting(McpServerStatus::state).isEqualTo(State.NOT_CONNECTED);

        assertThat(client.listTools()).extracting(McpTool::name)
                .containsExactly("read_file", "search_code", "write_file");
        McpToolResult result = client.callTool("search_code", Map.of("query", "Kafka"));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("PaymentProducer.java", "KafkaConfig.java");
        assertThat(files.calls("search_code")).isEqualTo(1);
        McpServerStatus status = client.servers().getFirst();
        assertThat(status.state()).isEqualTo(State.CONNECTED);
        assertThat(status.serverName()).isEqualTo("files");
        assertThat(status.serverVersion()).isEqualTo("1.0");
        assertThat(status.protocolVersion()).isNotBlank();
        assertThat(status.tools()).isEqualTo(3);
        assertThat(status.error()).isNull();
        assertThat(meters.get(ToolExecutor.METRIC).tag("tool", "search_code").tag("outcome", "SUCCESS").timer()
                .count()).isEqualTo(1);
    }

    @Test
    void discoveredToolsCarryTheirServerSchemaAndReadOnlyHint() {
        var files = server("files", false);
        files.restart(files.readFile(), files.writeFile());
        client = clientFor(Map.of("files", files.url()));

        assertThat(client.listTools()).containsExactly(
                new McpTool("files", "read_file", "read_file tool", TestMcpServer.PATH_SCHEMA, true),
                new McpTool("files", "write_file", "write_file tool", TestMcpServer.PATH_SCHEMA, false));
    }

    @Test
    void toolListIsCachedUntilTheServerAnnouncesAChange() {
        var files = server("files", true);
        files.restart(files.readFile());
        client = clientFor(Map.of("files", files.url()));
        assertThat(client.listTools()).extracting(McpTool::name).containsExactly("read_file");

        files.mcp().addTool(files.searchCode());   // sends notifications/tools/list_changed

        await(() -> client.listTools().stream().anyMatch(t -> t.name().equals("search_code")));
        assertThat(client.callTool("search_code", Map.of("query", "Kafka")).error()).isFalse();
    }

    @Test
    void severalServersAreMergedAndTheHigherPriorityWinsANameClash() {
        var files = server("files", false);
        files.restart(files.readFile(), files.searchCode());
        var git = server("git", false);
        git.restart(git.searchCode(), git.tool("git_log", true, Map.of("type", "object"),
                (t, args) -> TestMcpServer.text("abc123 fix")));
        client = clientFor(ordered("git", git.url(), "files", files.url()));   // git: priority 0, files: 1

        assertThat(client.listTools()).extracting(t -> t.server() + ":" + t.name())
                .containsExactly("git:search_code", "git:git_log", "files:read_file");
        assertThat(client.callTool("git_log", Map.of()).text()).isEqualTo("abc123 fix");
        client.callTool("search_code", Map.of("query", "Kafka"));
        assertThat(git.calls("search_code")).isEqualTo(1);
        assertThat(files.calls("search_code")).isZero();
        assertThat(client.serverOf("git_log")).contains("git");
    }

    @Test
    void aServerThatIsDownDoesNotHideTheOthers() {
        var files = server("files", false);
        files.restart(files.readFile());
        int downPort = TestMcpServer.freePort();
        client = clientFor(ordered("files", files.url(), "git", URI.create("http://127.0.0.1:" + downPort + "/mcp")));

        ToolRegistry.Catalog catalog = client.tools(false);

        assertThat(catalog.tools()).extracting(McpTool::name).containsExactly("read_file");
        assertThat(catalog.unavailable()).singleElement().satisfies(e -> {
            assertThat(e.server()).isEqualTo("git");
            assertThat(e.code()).isEqualTo(McpErrorCode.CONNECTION_FAILED);
            assertThat(e.getMessage()).contains("Git MCP server is not reachable at http://127.0.0.1:" + downPort,
                    "connection refused");
        });
        assertThat(client.callTool("read_file", Map.of("path", "a")).error()).isFalse();
        // an unknown name might be the down server's tool: that is an outage, not UNKNOWN_TOOL
        assertThatThrownBy(() -> client.callTool("git_log", Map.of()))
                .isInstanceOfSatisfying(McpUnavailableException.class, e -> assertThat(e.server()).isEqualTo("git"));
        assertThat(client.servers()).extracting(McpServerStatus::state)
                .containsExactly(State.CONNECTED, State.UNAVAILABLE);
    }

    // ── tool errors (returned) ────────────────────────────────────────────────────────────────────

    @Test
    void toolReportedFailureIsAToolError() {
        var files = server("files", false);
        files.restart(files.readFile());
        client = clientFor(Map.of("files", files.url()));

        assertThat(client.callTool("read_file", Map.of("path", "missing")))
                .isEqualTo(new McpToolResult("NOT_FOUND: missing", McpErrorCode.TOOL_ERROR));
    }

    @Test
    void unknownToolNeverReachesTheServer() {
        var files = server("files", false);
        files.restart(files.readFile());
        client = clientFor(Map.of("files", files.url()));

        McpToolResult result = client.callTool("no_such_tool", Map.of());

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.UNKNOWN_TOOL);
        assertThat(result.text()).isEqualTo("UNKNOWN_TOOL: no connected MCP server offers a tool named 'no_such_tool'");
    }

    @Test
    void toolRemovedWithoutNoticeIsUnknownAndTheListIsRefreshed() {
        var files = server("files", false);   // no listChanged: the client isn't told
        files.restart(files.readFile(), files.searchCode());
        client = clientFor(Map.of("files", files.url()));
        assertThat(client.listTools()).hasSize(2);

        files.mcp().removeTool("search_code");

        McpToolResult result = client.callTool("search_code", Map.of("query", "Kafka"));
        assertThat(result.errorCode()).isEqualTo(McpErrorCode.UNKNOWN_TOOL);
        assertThat(result.text()).contains("server 'files' no longer offers 'search_code'");
        assertThat(client.listTools()).extracting(McpTool::name).containsExactly("read_file");
    }

    @Test
    void argumentsAreCheckedAgainstTheSchemaBeforeTheServerSeesThem() {
        var files = server("files", false);
        files.restart(files.searchCode());
        client = clientFor(Map.of("files", files.url()));

        McpToolResult missing = client.callTool("search_code", Map.of());
        McpToolResult wrongType = client.callTool("search_code", Map.of("query", 42));
        McpToolResult empty = client.callTool("search_code", Map.of("query", ""));

        assertThat(List.of(missing, wrongType, empty)).extracting(McpToolResult::errorCode)
                .containsOnly(McpErrorCode.INVALID_ARGUMENTS);
        assertThat(missing.text()).startsWith("INVALID_ARGUMENTS: ").contains("query");
        assertThat(files.calls("search_code")).isZero();
    }

    @Test
    void invalidParamsFromTheServerAreInvalidArguments() {
        var files = server("files", false);
        files.restart(files.failing("strict", McpSchema.ErrorCodes.INVALID_PARAMS, "depth must be <= 8"));
        client = clientFor(Map.of("files", files.url()));

        assertThat(client.callTool("strict", Map.of()))
                .isEqualTo(new McpToolResult("INVALID_ARGUMENTS: depth must be <= 8", McpErrorCode.INVALID_ARGUMENTS));
    }

    @Test
    void jsonRpcErrorFromTheServerIsAServerError() {
        var files = server("files", false);
        files.restart(files.failing("broken", McpSchema.ErrorCodes.INTERNAL_ERROR, "index corrupt"));
        client = clientFor(Map.of("files", files.url()));

        McpToolResult result = client.callTool("broken", Map.of());

        assertThat(result).isEqualTo(new McpToolResult("SERVER_ERROR: index corrupt (JSON-RPC -32603)",
                McpErrorCode.SERVER_ERROR));
        assertThat(client.servers().getFirst().state()).isEqualTo(State.CONNECTED);   // the session is fine
    }

    @Test
    void toolsNotDeclaredReadOnlyAreRefused() {
        var files = server("files", false);
        files.restart(files.writeFile());
        client = clientFor(Map.of("files", files.url()));

        McpToolResult result = client.callTool("write_file", Map.of("path", "a"));

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.TOOL_NOT_ALLOWED);
        assertThat(files.calls("write_file")).isZero();
    }

    @Test
    void slowToolTimesOutWithoutBreakingTheSession() {
        var files = server("files", false);
        files.restart(files.slow(3_000), files.readFile());
        client = clientFor(Map.of("files", files.url()), Duration.ofSeconds(1), DataSize.ofMegabytes(4));
        assertThat(client.callTool("read_file", Map.of("path", "a")).error()).isFalse();
        var session = client.servers().getFirst().connectedAt();

        long start = System.nanoTime();
        McpToolResult result = client.callTool("slow", Map.of());

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_500);   // not retried
        assertThat(result.errorCode()).isEqualTo(McpErrorCode.TIMEOUT);
        assertThat(result.text()).isEqualTo("TIMEOUT: Files MCP server at " + files.url()
                + " did not answer tools/call within 1s");
        assertThat(files.calls("slow")).isEqualTo(1);
        assertThat(client.callTool("read_file", Map.of("path", "b")).error()).isFalse();
        assertThat(client.servers().getFirst().connectedAt()).isEqualTo(session);
    }

    // ── outages (thrown) ──────────────────────────────────────────────────────────────────────────

    @Test
    void serverDownIsAConnectionFailure() {
        int port = TestMcpServer.freePort();
        client = clientFor(Map.of("files", URI.create("http://127.0.0.1:" + port + "/mcp")));

        assertThatThrownBy(() -> client.callTool("read_file", Map.of("path", "a")))
                .isInstanceOfSatisfying(McpUnavailableException.class, e -> {
                    assertThat(e.code()).isEqualTo(McpErrorCode.CONNECTION_FAILED);
                    assertThat(e.getMessage()).startsWith("Files MCP server is not reachable at http://127.0.0.1:"
                            + port + "/mcp (connection refused)");
                });
        McpServerStatus status = client.servers().getFirst();
        assertThat(status.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(status.error()).isEqualTo(McpErrorCode.CONNECTION_FAILED);
    }

    @Test
    void oversizedResponseIsATransportError() {
        var files = server("files", false);
        files.restart(files.big(10_000), files.readFile());
        client = clientFor(Map.of("files", files.url()), Duration.ofSeconds(5), DataSize.ofKilobytes(2));

        assertThatThrownBy(() -> client.callTool("big", Map.of()))
                .isInstanceOfSatisfying(McpUnavailableException.class, e -> {
                    assertThat(e.code()).isEqualTo(McpErrorCode.TRANSPORT_ERROR);
                    assertThat(e.getMessage()).contains("larger than max-response-size (2048B)");
                });
        assertThat(files.calls("big")).isEqualTo(1);   // not retried: it would only be too large again
        assertThat(client.callTool("read_file", Map.of("path", "a")).error()).isFalse();   // recovers
    }

    @Test
    void expiredSessionIsReplacedTransparently() {
        var files = server("files", false);
        files.restart(files.readFile());
        client = clientFor(Map.of("files", files.url()));
        assertThat(client.callTool("read_file", Map.of("path", "a")).error()).isFalse();
        var first = client.servers().getFirst().connectedAt();

        files.forgetSessionsOnce();

        assertThat(client.callTool("read_file", Map.of("path", "b")))
                .isEqualTo(McpToolResult.success("content of b"));
        assertThat(client.servers().getFirst().connectedAt()).isAfterOrEqualTo(first);
    }

    @Test
    void reconnectsAfterTheServerRestartsAndRediscoversItsTools() {
        var files = server("files", false);
        files.restart(files.readFile());
        client = clientFor(Map.of("files", files.url()));
        assertThat(client.callTool("read_file", Map.of("path", "a")).error()).isFalse();

        files.stop();
        assertThatThrownBy(() -> client.callTool("read_file", Map.of("path", "a")))
                .isInstanceOf(McpUnavailableException.class);

        files.restart(files.readFile(), files.searchCode());
        assertThat(client.callTool("read_file", Map.of("path", "b")))
                .isEqualTo(McpToolResult.success("content of b"));
        assertThat(client.listTools()).extracting(McpTool::name).containsExactly("read_file", "search_code");
    }

    // ── roots ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approvedFoldersAreDeclaredAsRootsNowAndAfterAReconnect() {
        var files = server("files", false);
        files.restart(files.rootsTool());
        client = clientFor(Map.of("files", files.url()));
        client.approveRoot(Path.of("/data/early"));       // before connecting: sent at initialize
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/early");

        client.approveRoot(Path.of("/data/late/../later"));  // while connected: roots/list_changed
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/early,file:///data/later");

        files.restart(files.rootsTool());
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/early,file:///data/later");

        client.revokeRoot(Path.of("/data/early"));
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/later");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private TestMcpServer server(String name, boolean announceChanges) {
        var server = TestMcpServer.start(name, announceChanges);
        servers.add(server);
        return server;
    }

    private McpClientService clientFor(Map<String, URI> urls) {
        return clientFor(urls, Duration.ofSeconds(5), DataSize.ofMegabytes(4));
    }

    private McpClientService clientFor(Map<String, URI> urls, Duration requestTimeout, DataSize maxResponse) {
        var configured = new LinkedHashMap<String, McpClientProperties.Server>();
        urls.forEach((name, url) -> configured.put(name, new McpClientProperties.Server(url, configured.size(),
                Duration.ofSeconds(2), Duration.ofSeconds(5), requestTimeout, maxResponse)));
        var properties = new McpClientProperties(false, configured);
        var config = new McpClientConfiguration();
        McpRoots roots = config.mcpRoots();
        registry = config.toolRegistry(properties, new McpTransport(), roots,
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class));
        return new McpClientService(registry, config.toolExecutor(registry, meters), roots, properties);
    }

    private List<McpServerStatus> clientStatusWithoutConnecting() {
        return registry.connections().stream().map(c -> McpServerStatus.of(c, 0)).toList();
    }

    private static Map<String, URI> ordered(String first, URI firstUrl, String second, URI secondUrl) {
        var map = new LinkedHashMap<String, URI>();
        map.put(first, firstUrl);
        map.put(second, secondUrl);
        return map;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
