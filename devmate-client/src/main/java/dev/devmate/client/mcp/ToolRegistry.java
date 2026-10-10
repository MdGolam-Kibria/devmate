package dev.devmate.client.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tools of every configured MCP server, discovered through {@code tools/list} and never hardcoded. A server's
 * list is kept until its {@link McpConnection#toolsVersion()} changes (new session, or the server announced a change)
 * or {@link #invalidate} is called. Tool names are unique across servers: when two servers offer the same name, the
 * server with the higher priority (see {@link McpClientProperties}) wins and the other tool is left out (logged).
 */
public final class ToolRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** What is known right now: the usable tools, and the servers that could not be asked. */
    public record Catalog(List<McpTool> tools, List<McpUnavailableException> unavailable) {}

    /** A tool and the connection to call it on. */
    public record Entry(McpTool tool, McpConnection connection) {}

    private record Discovered(long version, List<McpTool> tools) {}

    private final List<McpConnection> connections;
    private final Map<String, Discovered> discovered = new ConcurrentHashMap<>();

    public ToolRegistry(List<McpConnection> connections) {
        this.connections = List.copyOf(connections);
    }

    public List<McpConnection> connections() {
        return connections;
    }

    /** The tools last discovered for a server, without contacting it (empty if never discovered). */
    public List<McpTool> cachedTools(String server) {
        Discovered known = discovered.get(server);
        return known == null ? List.of() : known.tools();
    }

    /**
     * All tools, re-listing each server whose list may be out of date ({@code refresh} re-lists every server).
     * A server that can't be reached is reported in {@link Catalog#unavailable()}; the others still count.
     */
    public Catalog tools(boolean refresh) {
        var byName = new LinkedHashMap<String, McpTool>();
        var unavailable = new ArrayList<McpUnavailableException>();
        for (McpConnection connection : connections) {
            try {
                for (McpTool tool : discover(connection, refresh)) {
                    McpTool first = byName.putIfAbsent(tool.name(), tool);
                    if (first != null) {
                        log.warn("MCP tool name clash: tool={} offered by server={} is ignored, server={} wins",
                                tool.name(), tool.server(), first.server());
                    }
                }
            } catch (McpUnavailableException e) {
                unavailable.add(e);
            }
        }
        return new Catalog(List.copyOf(byName.values()), List.copyOf(unavailable));
    }

    /**
     * Finds a tool by name. Up-to-date lists are used as they are; the others are re-listed first.
     *
     * @return empty if no server offers it
     * @throws McpUnavailableException if no reachable server offers it and a server could not be asked (the tool may
     *                                 well be there): the first such failure
     */
    public Optional<Entry> find(String name) {
        McpUnavailableException firstFailure = null;
        for (McpConnection connection : connections) {   // priority order: stop at the first server that has it
            try {
                Optional<McpTool> tool = discover(connection, false).stream()
                        .filter(t -> t.name().equals(name)).findFirst();
                if (tool.isPresent()) {
                    return Optional.of(new Entry(tool.get(), connection));
                }
            } catch (McpUnavailableException e) {
                firstFailure = firstFailure == null ? e : firstFailure;
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return Optional.empty();
    }

    /** Forgets a server's tools, e.g. after it said a listed tool doesn't exist; the next lookup re-lists them. */
    public void invalidate(String server) {
        discovered.remove(server);
    }

    private List<McpTool> discover(McpConnection connection, boolean refresh) {
        Discovered known = discovered.get(connection.name());
        if (!refresh && known != null && known.version() == connection.toolsVersion()) {
            return known.tools();
        }
        // The version is read inside the request: it belongs to the session the tools came from.
        Discovered fresh = connection.call("tools/list",
                session -> new Discovered(connection.toolsVersion(), session.listTools()), true);
        discovered.put(connection.name(), fresh);
        log.info("MCP tools discovered server={} tools={}", connection.name(), fresh.tools().size());
        return fresh.tools();
    }

    @Override
    public void close() {
        connections.forEach(McpConnection::close);
    }
}
