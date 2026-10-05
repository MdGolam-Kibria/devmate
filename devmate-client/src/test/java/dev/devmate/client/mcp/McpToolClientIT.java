package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/**
 * {@link McpToolClient} over the real protocol (Streamable HTTP) against an MCP server built with the official SDK on
 * an embedded Tomcat, so the client is tested without depending on the files MCP module.
 */
class McpToolClientIT {

    private static final Map<String, Object> PATH_SCHEMA =
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")));

    private WebServer server;
    private McpSyncServer mcpServer;
    private McpToolClient client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.destroy();
        }
        stopServer();
    }

    @Test
    void discoversToolsWithTheirSchemaAndReadOnlyHint() {
        client = clientFor(startServer(0));

        assertThat(client.listTools()).containsExactlyInAnyOrder(
                new McpTool("read_file", "Read a file", PATH_SCHEMA, true),
                new McpTool("write_file", "Write a file", PATH_SCHEMA, false),
                new McpTool("roots", "Client roots", Map.of("type", "object"), false));
    }

    @Test
    void callsToolsAndMapsResultsAndToolErrors() {
        client = clientFor(startServer(0));

        assertThat(client.callTool("read_file", Map.of("path", "a.txt")))
                .isEqualTo(new McpToolResult("content of a.txt", false));
        assertThat(client.callTool("read_file", Map.of("path", "missing")))
                .isEqualTo(new McpToolResult("NOT_FOUND: missing", true));
    }

    @Test
    void unknownToolIsAnErrorResultNotAnOutage() {
        client = clientFor(startServer(0));

        McpToolResult result = client.callTool("no_such_tool", Map.of());

        assertThat(result.error()).isTrue();
        assertThat(result.text()).startsWith("MCP_ERROR:");
    }

    @Test
    void serverDownIsReportedAsUnavailable() throws Exception {
        int port = freePort();
        client = clientFor(port);

        assertThatThrownBy(() -> client.listTools())
                .isInstanceOf(McpUnavailableException.class)
                .hasMessageContaining("Files MCP server is not reachable at http://127.0.0.1:" + port + "/mcp");
    }

    @Test
    void reconnectsAfterTheServerRestarts() {
        int port = startServer(0);
        client = clientFor(port);
        assertThat(client.callTool("read_file", Map.of("path", "a")).error()).isFalse();

        stopServer();
        assertThatThrownBy(() -> client.listTools()).isInstanceOf(McpUnavailableException.class);

        startServer(port);
        assertThat(client.callTool("read_file", Map.of("path", "b")))
                .isEqualTo(new McpToolResult("content of b", false));
    }

    @Test
    void approvedFoldersAreDeclaredAsRootsNowAndAfterAReconnect() {
        int port = startServer(0);
        client = clientFor(port);
        client.approveRoot(Path.of("/data/early"));       // before connecting: sent at initialize
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/early");

        client.approveRoot(Path.of("/data/late/../later"));  // while connected: roots/list_changed
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/early,file:///data/later");

        stopServer();
        startServer(port);
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/early,file:///data/later");

        client.revokeRoot(Path.of("/data/early"));
        assertThat(client.callTool("roots", Map.of()).text()).isEqualTo("file:///data/later");
    }

    private McpToolClient clientFor(int port) {
        var properties = new FilesMcpProperties(URI.create("http://127.0.0.1:" + port + "/mcp"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        ObjectProvider<BuildProperties> noBuildInfo = new StaticListableBeanFactory().getBeanProvider(BuildProperties.class);
        return new McpToolClient(properties, noBuildInfo);
    }

    private int startServer(int port) {
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        mcpServer = McpServer.sync(transport)
                .serverInfo("test-files", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tool("read_file", "Read a file", true), tool("write_file", "Write a file", false), rootsTool())
                .build();
        var factory = new TomcatServletWebServerFactory(port);
        factory.setAddress(InetAddress.getLoopbackAddress());
        server = factory.getWebServer(context -> {
            var servlet = context.addServlet("mcp", transport);
            servlet.addMapping("/mcp", "/mcp/*");
            servlet.setAsyncSupported(true);
        });
        server.start();
        return server.getPort();
    }

    private void stopServer() {
        if (mcpServer != null) {
            mcpServer.close();
            mcpServer = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static SyncToolSpecification tool(String name, String description, boolean readOnly) {
        var tool = McpSchema.Tool.builder().name(name).description(description).inputSchema(PATH_SCHEMA)
                .annotations(new McpSchema.ToolAnnotations(null, readOnly, !readOnly, true, false, null))
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            String path = String.valueOf(request.arguments().get("path"));
            return "missing".equals(path)
                    ? CallToolResult.builder().addTextContent("NOT_FOUND: missing").isError(true).build()
                    : CallToolResult.builder().addTextContent("content of " + path).isError(false).build();
        }).build();
    }

    /** Echoes the client's roots, as the server sees them through roots/list. */
    private static SyncToolSpecification rootsTool() {
        var tool = McpSchema.Tool.builder().name("roots").description("Client roots").inputSchema(Map.of("type", "object"))
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> CallToolResult.builder()
                .addTextContent(exchange.listRoots().roots().stream().map(McpSchema.Root::uri)
                        .collect(Collectors.joining(",")))
                .build()).build();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
