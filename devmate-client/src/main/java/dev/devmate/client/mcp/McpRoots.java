package dev.devmate.client.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Folders the user approved, declared to every server as MCP roots. In memory only: an approval lasts until DevMate
 * restarts, and every new session declares them again at {@code initialize}.
 */
final class McpRoots {

    private final LinkedHashSet<String> uris = new LinkedHashSet<>();  // guarded by this

    /** Adds a folder; empty if it was already approved. */
    synchronized Optional<McpSchema.Root> add(Path folder) {
        String uri = uri(folder);
        return uris.add(uri) ? Optional.of(root(uri)) : Optional.empty();
    }

    /** Removes a folder; empty if it wasn't approved. */
    synchronized Optional<String> remove(Path folder) {
        String uri = uri(folder);
        return uris.remove(uri) ? Optional.of(uri) : Optional.empty();
    }

    synchronized List<McpSchema.Root> list() {
        return uris.stream().map(McpRoots::root).toList();
    }

    synchronized int size() {
        return uris.size();
    }

    private static McpSchema.Root root(String uri) {
        return new McpSchema.Root(uri, Path.of(URI.create(uri)).toString());
    }

    /** {@code file:} URI without a trailing slash, so the same folder always maps to the same root. */
    private static String uri(Path folder) {
        try {
            return new URI("file", "", folder.toAbsolutePath().normalize().toString(), null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid folder path", e);
        }
    }
}
