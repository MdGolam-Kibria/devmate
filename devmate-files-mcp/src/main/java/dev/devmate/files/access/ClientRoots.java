package dev.devmate.files.access;

import dev.devmate.files.config.FilesProperties;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * The policy for one tool call: the configured roots, plus the client's own roots (MCP {@code roots/list}) when
 * {@code devmate.filesystem.allow-client-roots} is on. The client is asked on every call, so folders it adds or
 * removes take effect immediately and never leak into another client's session.
 */
@Component
public class ClientRoots {

    private static final Logger log = LoggerFactory.getLogger(ClientRoots.class);

    private final FileAccessPolicy policy;
    private final boolean enabled;

    public ClientRoots(FileAccessPolicy policy, FilesProperties properties) {
        this.policy = policy;
        this.enabled = properties.allowClientRoots();
        if (enabled) {
            log.warn("devmate.filesystem.allow-client-roots=true: MCP clients may add folders to the sandbox");
        }
    }

    /** {@code context} may be null (direct calls in tests): the configured policy applies. */
    public FileAccessPolicy policyFor(McpSyncRequestContext context) {
        if (!enabled || context == null || !context.rootsEnabled()) {
            return policy;
        }
        McpSchema.ListRootsResult result;
        try {
            result = context.roots();
        } catch (RuntimeException e) {
            log.warn("roots/list to the client failed error={}", e.getClass().getSimpleName());
            return policy;
        }
        if (result == null || result.roots() == null) {
            return policy;
        }
        return policy.withAdditionalRoots(toPaths(result.roots()));
    }

    /** Only {@code file:} URIs count; anything else is ignored. */
    static List<Path> toPaths(List<McpSchema.Root> roots) {
        var paths = new ArrayList<Path>();
        for (McpSchema.Root root : roots) {
            try {
                URI uri = URI.create(root.uri());
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    paths.add(Path.of(uri));
                }
            } catch (RuntimeException e) {
                // malformed root URI: not a folder we can grant
            }
        }
        return paths;
    }
}
