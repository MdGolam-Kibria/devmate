package dev.devmate.client.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * DevMate's MCP client: the one entry point the rest of DevMate (folder Q&amp;A, the {@code /api/mcp} API, later the
 * agent) uses to reach MCP servers. Connect → initialize → discover tools → register them → execute a tool → result,
 * with no tool hardcoded.
 *
 * <pre>
 * McpClientService ─► ToolRegistry ─► McpConnection (one per server) ─► McpSession ─► McpTransport (Streamable HTTP)
 *         └─────────► ToolExecutor ──┘
 * </pre>
 */
@Service
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final McpRoots roots;
    private final McpClientProperties properties;

    McpClientService(ToolRegistry registry, ToolExecutor executor, McpRoots roots, McpClientProperties properties) {
        this.registry = registry;
        this.executor = executor;
        this.roots = roots;
        this.properties = properties;
    }

    /** Every configured server's status, after trying to connect to and discover each one that isn't up to date. */
    public List<McpServerStatus> servers() {
        registry.tools(false);
        return registry.connections().stream()
                .map(connection -> McpServerStatus.of(connection, registry.cachedTools(connection.name()).size()))
                .toList();
    }

    /** The discovered tools of all servers; {@code refresh} re-lists every server. */
    public ToolRegistry.Catalog tools(boolean refresh) {
        return registry.tools(refresh);
    }

    /** The tools of the servers that can be reached now. */
    public List<McpTool> listTools() {
        return registry.tools(false).tools();
    }

    /**
     * Calls a tool on whichever server offers it.
     *
     * @throws McpUnavailableException if that server can't be used
     * @see ToolExecutor#execute
     */
    public McpToolResult callTool(String name, Map<String, Object> arguments) {
        return executor.execute(name, arguments);
    }

    /** The server a tool was last discovered on (no network call), for reporting. */
    public Optional<String> serverOf(String tool) {
        return registry.connections().stream().map(McpConnection::name)
                .filter(server -> registry.cachedTools(server).stream().anyMatch(t -> t.name().equals(tool)))
                .findFirst();
    }

    /** Declares a folder the user approved as an MCP root, now and on every future session, to every server. */
    public void approveRoot(Path folder) {
        roots.add(folder).ifPresent(root -> {
            log.info("MCP root approved by the user rootCount={}", roots.size());
            registry.connections().forEach(connection -> connection.addRoot(root));
        });
    }

    /** Withdraws an approval (e.g. the server didn't accept it). */
    public void revokeRoot(Path folder) {
        roots.remove(folder).ifPresent(uri -> registry.connections().forEach(connection -> connection.removeRoot(uri)));
    }

    /** Connects and discovers in the background once DevMate is up, so the log shows early whether servers work. */
    @EventListener(ApplicationReadyEvent.class)
    void discoverOnStartup() {
        if (!properties.connectOnStartup() || registry.connections().isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("mcp-startup-discovery").start(() -> {
            try {
                ToolRegistry.Catalog catalog = registry.tools(false);
                log.info("MCP startup discovery: servers={} tools={} unavailable={}", registry.connections().size(),
                        catalog.tools().size(),
                        catalog.unavailable().stream().map(McpUnavailableException::server).toList());
            } catch (RuntimeException e) {   // only a log line is at stake: the first real request retries anyway
                log.warn("MCP startup discovery failed error={}", e.getClass().getSimpleName());
            }
        });
    }
}
