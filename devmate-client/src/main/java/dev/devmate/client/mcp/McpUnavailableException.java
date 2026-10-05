package dev.devmate.client.mcp;

/** The MCP server could not be reached or broke the protocol. The message is safe to show to users. */
public final class McpUnavailableException extends RuntimeException {

    public McpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
