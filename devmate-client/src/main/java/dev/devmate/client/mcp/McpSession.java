package dev.devmate.client.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One initialized MCP session with a server: what {@code initialize} negotiated, plus the requests DevMate sends on
 * it. The only class besides {@link McpConnection} that touches the SDK client; the rest of DevMate sees
 * {@link McpTool} and {@link McpToolResult}.
 */
public final class McpSession {

    private final String server;
    private final McpSyncClient client;
    private final McpSchema.InitializeResult init;
    private final Instant openedAt = Instant.now();

    private McpSession(String server, McpSyncClient client, McpSchema.InitializeResult init) {
        this.server = server;
        this.client = client;
        this.init = init;
    }

    /** Runs the {@code initialize} handshake; closes the client if it fails. */
    static McpSession initialize(String server, McpSyncClient client) {
        try {
            return new McpSession(server, client, Objects.requireNonNull(client.initialize(), "initialize result"));
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    public String serverName() {
        return init.serverInfo() == null ? null : init.serverInfo().name();
    }

    public String serverVersion() {
        return init.serverInfo() == null ? null : init.serverInfo().version();
    }

    public String protocolVersion() {
        return init.protocolVersion();
    }

    public boolean offersTools() {
        return init.capabilities() != null && init.capabilities().tools() != null;
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** {@code tools/list}, all pages. A server without the tools capability has none. */
    public List<McpTool> listTools() {
        if (!offersTools()) {
            return List.of();
        }
        return client.listTools().tools().stream()
                .map(tool -> new McpTool(server, tool.name(), tool.description(), tool.inputSchema(),
                        tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint())))
                .toList();
    }

    /**
     * {@code tools/call}. Text content is kept; other content (images, resources) is replaced by a placeholder line.
     *
     * @throws io.modelcontextprotocol.spec.McpError on a JSON-RPC error answer
     */
    public McpToolResult callTool(String name, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
        String text = result.content() == null ? "" : result.content().stream()
                .map(content -> content instanceof TextContent textContent
                        ? textContent.text()
                        : "[" + content.type() + " content omitted by DevMate]")
                .collect(Collectors.joining("\n"));
        return Boolean.TRUE.equals(result.isError())
                ? new McpToolResult(text, McpErrorCode.TOOL_ERROR)
                : McpToolResult.success(text);
    }

    void addRoot(McpSchema.Root root) {
        client.addRoot(root);
    }

    void removeRoot(String uri) {
        client.removeRoot(uri);
    }

    /**
     * Ends the session. {@code graceful} also tells the server (HTTP DELETE), for a session that still works; a broken
     * one is just released locally.
     */
    void close(boolean graceful) {
        try {
            if (graceful) {
                client.closeGracefully();
            } else {
                client.close();
            }
        } catch (RuntimeException ignored) {
            // already broken: nothing left to release
        }
    }
}
