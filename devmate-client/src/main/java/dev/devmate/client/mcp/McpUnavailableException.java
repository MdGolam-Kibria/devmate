package dev.devmate.client.mcp;

/**
 * An MCP server could not be used: not reachable, too slow, or it broke the session or transport. The message is safe
 * to show to users (no server output, no arguments).
 */
public final class McpUnavailableException extends RuntimeException {

    private final McpErrorCode code;
    private final String server;

    public McpUnavailableException(McpErrorCode code, String server, String message, Throwable cause) {
        super(message, cause);
        if (!code.unavailable()) {
            throw new IllegalArgumentException(code + " is a tool result, not an outage");
        }
        this.code = code;
        this.server = server;
    }

    public McpErrorCode code() {
        return code;
    }

    /** The configured server name, e.g. {@code files}. */
    public String server() {
        return server;
    }
}
