package dev.devmate.client.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * DevMate's connection to the files MCP server over Streamable HTTP (official SDK client): initialize → tools/list →
 * tools/call. Connects on first use and reconnects once after a transport failure (e.g. the server restarted and the
 * session is gone), so DevMate and the server can start and stop independently.
 *
 * <p>Folders the user approved are declared as MCP <em>roots</em>. A files MCP server started with
 * {@code allow-client-roots=true} asks for them on every tool call and lets this connection read them. They live
 * in memory only: an approval lasts until DevMate restarts, and survives a reconnect.
 */
@Component
public class McpToolClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpToolClient.class);

    private final FilesMcpProperties properties;
    private final String clientVersion;
    private McpSyncClient client;  // guarded by this
    private final LinkedHashSet<String> approvedRoots = new LinkedHashSet<>();  // root URIs, guarded by this

    public McpToolClient(FilesMcpProperties properties, ObjectProvider<BuildProperties> build) {
        this.properties = properties;
        BuildProperties info = build.getIfAvailable();
        this.clientVersion = info == null ? "dev" : info.getVersion();
    }

    /** The server's tools, as discovered now (never cached: servers may change their tool list). */
    public List<McpTool> listTools() {
        return withClient("tools/list", client -> client.listTools().tools().stream()
                .map(tool -> new McpTool(tool.name(), tool.description(), tool.inputSchema(),
                        tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint())))
                .toList());
    }

    /**
     * Calls a tool. A tool failure ({@code isError}) or a protocol error for this call (unknown tool, invalid
     * arguments) is returned as an error result. After a transport failure the call is sent once more on a new
     * session, so only pass tools that are safe to repeat (DevMate only calls read-only tools).
     *
     * @throws McpUnavailableException if the server can't be reached
     */
    public McpToolResult callTool(String name, Map<String, Object> arguments) {
        return withClient("tools/call", client -> {
            try {
                return toResult(client.callTool(new McpSchema.CallToolRequest(name, arguments)));
            } catch (McpError e) {
                return new McpToolResult("MCP_ERROR: " + e.getMessage(), true);
            }
        });
    }

    /** Declares a folder the user approved as a root, now and on every future connection. */
    public void approveRoot(Path folder) {
        String uri = rootUri(folder);
        McpSyncClient current;
        synchronized (this) {
            if (!approvedRoots.add(uri)) {
                return;
            }
            current = client;
        }
        log.info("MCP root approved by the user rootCount={}", approvedRoots.size());
        if (current != null) {
            try {
                current.addRoot(new McpSchema.Root(uri, folder.toString()));
            } catch (RuntimeException e) {
                reset(current);  // the next connection declares the root at initialize
            }
        }
    }

    /** Withdraws an approval (e.g. the server didn't accept it). */
    public void revokeRoot(Path folder) {
        String uri = rootUri(folder);
        McpSyncClient current;
        synchronized (this) {
            if (!approvedRoots.remove(uri)) {
                return;
            }
            current = client;
        }
        if (current != null) {
            try {
                current.removeRoot(uri);
            } catch (RuntimeException e) {
                reset(current);
            }
        }
    }

    /** The configured endpoint, for messages. */
    public URI url() {
        return properties.url();
    }

    private <T> T withClient(String operation, Function<McpSyncClient, T> call) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            McpSyncClient current = null;
            try {
                current = connected();
                return call.apply(current);
            } catch (RuntimeException e) {
                failure = e;
                log.warn("MCP {} failed attempt={} url={} error={}", operation, attempt, properties.url(),
                        e.getClass().getSimpleName());
                reset(current);
            }
        }
        throw new McpUnavailableException("Files MCP server is not reachable at " + properties.url()
                + " (start devmate-files-mcp, or set DEVMATE_FILES_MCP_URL)", failure);
    }

    private synchronized McpSyncClient connected() {
        if (client == null) {
            URI url = properties.url();
            var transport = HttpClientStreamableHttpTransport
                    .builder(url.getScheme() + "://" + url.getRawAuthority())
                    .endpoint(url.getRawPath() == null || url.getRawPath().isEmpty() ? "/mcp" : url.getRawPath())
                    .connectTimeout(properties.connectTimeout())
                    .build();
            McpSyncClient created = McpClient.sync(transport)
                    .clientInfo(McpSchema.Implementation.builder("devmate-client", clientVersion).build())
                    .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                    .roots(approvedRoots.stream().map(uri -> new McpSchema.Root(uri, Path.of(URI.create(uri)).toString()))
                            .toList())
                    .requestTimeout(properties.requestTimeout())
                    .initializationTimeout(properties.requestTimeout())
                    .build();
            try {
                McpSchema.InitializeResult init = created.initialize();
                log.info("MCP connected url={} server={} version={} protocol={}", url, init.serverInfo().name(),
                        init.serverInfo().version(), init.protocolVersion());
            } catch (RuntimeException e) {
                created.close();
                throw e;
            }
            client = created;
        }
        return client;
    }

    /** Drops the connection if it is still the one that failed, so the next call starts a fresh session. */
    private synchronized void reset(McpSyncClient failed) {
        if (failed != null && client == failed) {
            client = null;
            try {
                failed.close();
            } catch (RuntimeException ignored) {
                // already broken
            }
        }
    }

    /** {@code file:} URI without a trailing slash, so the same folder always maps to the same root. */
    private static String rootUri(Path folder) {
        try {
            return new URI("file", "", folder.toAbsolutePath().normalize().toString(), null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid folder path", e);
        }
    }

    private static McpToolResult toResult(CallToolResult result) {
        String text = result.content() == null ? "" : result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .collect(Collectors.joining("\n"));
        return new McpToolResult(text, Boolean.TRUE.equals(result.isError()));
    }

    @Override
    public synchronized void destroy() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
    }
}
